package com.cartmate.cartmate.boundedContext.memberService.member.app;

import com.cartmate.cartmate.boundedContext.memberService.member.domain.*;
import com.cartmate.cartmate.boundedContext.memberService.member.out.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// 탈퇴 확정: 조건부 UPDATE로 취소와 하나만 성공, 개인정보 삭제 범위, 같은 이메일 재가입, 다른 서비스가 받아 갈 기간 조회
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({MemberWithdrawalFinalizeUseCase.class, MemberWithdrawalFinalizeTest.Config.class})
class MemberWithdrawalFinalizeTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    static final VerificationMailSender MAIL = mock(VerificationMailSender.class);

    @TestConfiguration
    @EnableJpaAuditing
    static class Config {
        @Bean
        SecurityAlertMailer securityAlertMailer() {
            return new SecurityAlertMailer(MAIL, Runnable::run);
        }
    }

    private static final LocalDateTime REQUESTED = LocalDateTime.of(2026, 10, 1, 12, 0);
    private static final LocalDateTime DUE = REQUESTED.plusDays(MemberWithdrawalUseCase.GRACE_DAYS);

    @Autowired MemberWithdrawalFinalizeUseCase useCase;
    @Autowired MemberRepository memberRepository;
    @Autowired SellerRepository sellerRepository;
    @Autowired MemberAddressRepository addressRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired TrustedDeviceRepository trustedDeviceRepository;
    @Autowired MemberOtpRepository otpRepository;
    @Autowired MemberBackupCodeRepository backupCodeRepository;
    @Autowired MemberSuspensionRepository suspensionRepository;

    private Member pendingMember(String email, String nickname) {
        Member member = Member.create(email, "encoded", nickname, "010-1234-5678", LocalDate.of(1990, 5, 5));
        member.requestWithdrawal(REQUESTED);
        return memberRepository.saveAndFlush(member);
    }

    @Test
    void 확정하면_개인정보를_덮어쓰고_딸린_정보는_지우며_정지_이력은_남긴다() {
        Member member = Member.create("leave@cartmate.com", "encoded", "떠날회원", "010-1234-5678", LocalDate.of(1990, 5, 5));
        member.becomeSeller();
        member.requestWithdrawal(REQUESTED);
        member = memberRepository.saveAndFlush(member);
        Long id = member.getId();
        sellerRepository.saveAndFlush(Seller.create(member, SellerType.BUSINESS, "상호", "대표", "123-45-67890",
                "010-1111-2222", "KB", "예금주", "1234567890"));
        addressRepository.saveAndFlush(MemberAddress.create(member, "집", "받는이", "010-3333-4444", "12345", "서울", "101호"));
        refreshTokenRepository.saveAndFlush(RefreshToken.issue(id, "rt", DUE.plusDays(1), false, "pwd"));
        trustedDeviceRepository.saveAndFlush(TrustedDevice.register(id, "td", "Chrome", REQUESTED, DUE.plusDays(1)));
        otpRepository.saveAndFlush(MemberOtp.pending(id, "secret"));
        backupCodeRepository.saveAndFlush(MemberBackupCode.of(id, "code"));
        suspensionRepository.saveAndFlush(MemberSuspension.create(member, SuspensionReason.OTHER, "과거 정지",
                SuspensionDuration.PERMANENT, 99L, REQUESTED.minusDays(100)));

        assertThat(useCase.finalizeOne(id, DUE)).isTrue();

        Member after = memberRepository.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(after.getDeletedAt()).isEqualTo(DUE);
        assertThat(after.getEmail()).isEqualTo(Member.withdrawnEmail(id));
        assertThat(after.getNickname()).isEqualTo(Member.withdrawnNickname(id));
        assertThat(after.getPhone()).isEmpty();
        assertThat(after.getBirthDate()).isEqualTo(Member.WITHDRAWN_BIRTH_DATE);
        assertThat(after.getPassword()).isEqualTo(Member.WITHDRAWN_PASSWORD);

        Seller seller = sellerRepository.findByMemberId(id).orElseThrow(); // 행은 남는다 (seller_id 참조)
        assertThat(seller.getBusinessName()).isNull();
        assertThat(seller.getRepresentativeName()).isNull();
        assertThat(seller.getBusinessNumber()).isNull();
        assertThat(seller.getPhoneNumber()).isEmpty();
        assertThat(seller.getBankName()).isNull();
        assertThat(seller.getAccountHolder()).isNull();
        assertThat(seller.getAccountNumber()).isNull();

        assertThat(addressRepository.countByMemberId(id)).isZero();
        assertThat(refreshTokenRepository.count()).isZero();
        assertThat(trustedDeviceRepository.count()).isZero();
        assertThat(otpRepository.findByMemberId(id)).isEmpty();
        assertThat(backupCodeRepository.countByMemberId(id)).isZero();
        assertThat(suspensionRepository.count()).isOne();

        // 같은 이메일·닉네임·사업자번호로 다시 가입·등록할 수 있다
        assertThat(memberRepository.existsByEmail("leave@cartmate.com")).isFalse();
        assertThat(memberRepository.existsByNickname("떠날회원")).isFalse();
        assertThat(sellerRepository.existsByBusinessNumber("123-45-67890")).isFalse();
        // 완료 메일은 커밋 뒤에만 나간다 (이 테스트는 롤백되므로 확인하지 않는다)
    }

    @Test
    void 확정과_취소는_둘_중_하나만_성공한다() {
        Long canceledFirst = pendingMember("a@cartmate.com", "취소먼저").getId();
        assertThat(memberRepository.cancelWithdrawal(canceledFirst, MemberStatus.WITHDRAWAL_PENDING, MemberStatus.ACTIVE)).isOne();
        assertThat(useCase.finalizeOne(canceledFirst, DUE)).isFalse();
        assertThat(memberRepository.findById(canceledFirst).orElseThrow().getEmail()).isEqualTo("a@cartmate.com");

        Long finalizedFirst = pendingMember("b@cartmate.com", "확정먼저").getId();
        assertThat(useCase.finalizeOne(finalizedFirst, DUE)).isTrue();
        assertThat(memberRepository.cancelWithdrawal(finalizedFirst, MemberStatus.WITHDRAWAL_PENDING, MemberStatus.ACTIVE)).isZero();
        assertThat(useCase.finalizeOne(finalizedFirst, DUE)).isFalse(); // 두 번 돌아도 다시 확정하지 않는다
        assertThat(memberRepository.findById(finalizedFirst).orElseThrow().getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
    }

    @Test
    void 유예_기간이_끝나기_전에는_대상이_아니고_확정되지_않는다() {
        Long id = pendingMember("c@cartmate.com", "아직대기").getId();

        assertThat(useCase.findDueIds(DUE.minusSeconds(1), 0L)).isEmpty();
        assertThat(useCase.finalizeOne(id, DUE.minusSeconds(1))).isFalse();
        assertThat(memberRepository.findById(id).orElseThrow().getStatus()).isEqualTo(MemberStatus.WITHDRAWAL_PENDING);

        assertThat(useCase.findDueIds(DUE, 0L)).containsExactly(id);
        assertThat(useCase.findDueIds(DUE, id)).isEmpty(); // 커서 다음부터
    }

    @Test
    void 다른_서비스는_확정_시각이_from_이상_to_미만인_회원을_받는다() {
        Long first = pendingMember("d@cartmate.com", "첫째").getId();
        Long second = pendingMember("e@cartmate.com", "둘째").getId();
        useCase.finalizeOne(first, DUE);
        useCase.finalizeOne(second, DUE.plusDays(1));

        assertThat(useCase.findWithdrawnIds(null, DUE.plusDays(1))).containsExactly(first);
        assertThat(useCase.findWithdrawnIds(DUE.plusDays(1), DUE.plusDays(2))).containsExactly(second);
        assertThat(useCase.findWithdrawnIds(DUE, DUE)).isEmpty();
    }
}
