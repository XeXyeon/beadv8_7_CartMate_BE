package com.cartmate.cartmate.boundedContext.memberService.member.app;

import com.cartmate.cartmate.boundedContext.memberService.member.domain.Member;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.MemberStatus;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.SuspensionReleaseType;
import com.cartmate.cartmate.boundedContext.memberService.member.dto.*;
import com.cartmate.cartmate.boundedContext.memberService.member.exception.MemberErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.page.PageResponse;
import com.cartmate.cartmate.global.security.SecurityUser;
import com.cartmate.cartmate.shared.member.dto.MemberDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

// member 모듈의 정문. Controller와 다른 모듈은 여기만 호출한다.
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberFacade {
    private final MemberSupport memberSupport;
    private final MemberJoinUseCase memberJoinUseCase;
    private final MemberSendPhoneCodeUseCase memberSendPhoneCodeUseCase;
    private final MemberVerifyPhoneCodeUseCase memberVerifyPhoneCodeUseCase;
    private final MemberEmailVerificationUseCase memberEmailVerificationUseCase;
    private final MemberProfileUseCase memberProfileUseCase;
    private final MemberChangePasswordUseCase memberChangePasswordUseCase;
    private final AdminMemberSearchUseCase adminMemberSearchUseCase;
    private final AdminMemberDetailUseCase adminMemberDetailUseCase;
    private final AdminMemberStatusUseCase adminMemberStatusUseCase;
    private final MemberSellerUseCase memberSellerUseCase;
    private final MemberSellerAccountVerifyUseCase memberSellerAccountVerifyUseCase;
    private final MyPageDashboardUseCase myPageDashboardUseCase;
    private final MemberTokenUseCase memberTokenUseCase;
    private final MemberAddressUseCase memberAddressUseCase;
    private final AdminDashboardUseCase adminDashboardUseCase;
    private final MemberWithdrawalUseCase memberWithdrawalUseCase;
    private final MemberWithdrawalFinalizeUseCase memberWithdrawalFinalizeUseCase;
    private final TransactionTemplate transactionTemplate;


    @Transactional
    public MemberDto join(SignupRequest request) {
        return memberJoinUseCase.join(request);
    }

    @Transactional(readOnly = true)
    public Optional<MemberDto> findById(Long id) {
        return memberSupport.findById(id).map(Member::toDto);
    }

    @Transactional(readOnly = true)
    public long count() {
        return memberSupport.count();
    }

    @Transactional(readOnly = true)
    public boolean isEmailAvailable(String email) {
        return !memberSupport.existsByEmail(email);
    }

    @Transactional(readOnly = true)
    public boolean isNicknameAvailable(String nickname) {
        return !memberSupport.existsByNickname(nickname);
    }

    // 트랜잭션 없음: 메일 발송(SMTP) 동안 DB 연결을 잡지 않는다
    public int sendEmailCode(String email) {
        return memberEmailVerificationUseCase.send(email, Instant.now());
    }

    public void verifyEmailCode(String email, String code) {
        memberEmailVerificationUseCase.verify(email, code, Instant.now());
    }

    public int sendPhoneCode(String phone) {
        return memberSendPhoneCodeUseCase.send(phone);
    }

    public void verifyPhoneCode(String phone, String code) {
        memberVerifyPhoneCodeUseCase.verify(phone, code);
    }

    private final MemberLoginUseCase memberLoginUseCase;
    private final MemberLoginMfaUseCase memberLoginMfaUseCase;
    private final TrustedDeviceUseCase trustedDeviceUseCase;
    private final AdminOtpUseCase adminOtpUseCase;

    // 트랜잭션 없음: 2차 인증 메일(SMTP)을 보내는 동안 DB 연결을 잡지 않는다 (토큰·기기 저장은 저장소가 각자 연다)
    // trustedDeviceToken: 쿠키의 신뢰 기기 토큰 (없으면 null)
    public LoginOutcome login(String email, String password, boolean remember, String trustedDeviceToken) {
        return memberLoginUseCase.login(email, password, remember, trustedDeviceToken, Instant.now());
    }

    @Transactional
    public LoginOutcome verifyLoginMfa(String challengeId, String code, boolean trustDevice, String deviceName) {
        return memberLoginMfaUseCase.verify(challengeId, code, trustDevice, deviceName, Instant.now());
    }

    public int resendLoginMfa(String challengeId) {
        return memberLoginMfaUseCase.resend(challengeId, Instant.now());
    }

    // ===== 관리자 OTP 등록 (이메일까지 확인한 관리자 세션에서) =====
    // 트랜잭션 없음: 동시에 와도 같은 비밀키를 주려고 저장·재조회를 각각 짧은 트랜잭션으로 (AdminOtpUseCase.startEnrollment)
    public OtpEnrollmentResponse startOtpEnrollment(Long memberId) {
        return adminOtpUseCase.startEnrollment(memberId);
    }

    // 등록 완료: 백업 코드를 만들고, 다른 기기의 세션은 모두 끊고, OTP를 거친 새 토큰을 준다 (알림 메일은 커밋 뒤)
    @Transactional
    public OtpEnrollmentResult confirmOtpEnrollment(Long memberId, String code) {
        List<String> backupCodes = adminOtpUseCase.confirmEnrollment(memberId, code, Instant.now());
        memberTokenUseCase.revokeAll(memberId);
        Member member = memberSupport.findById(memberId)
                .orElseThrow(() -> new ServiceException(MemberErrorCode.MEMBER_NOT_FOUND));
        TokenPair tokens = memberTokenUseCase.issue(member, false,
                List.of(MemberTokenUseCase.AMR_PWD, SecurityUser.AMR_OTP));
        return new OtpEnrollmentResult(member.toDto(), tokens, backupCodes);
    }

    public record OtpEnrollmentResult(MemberDto member, TokenPair tokens, List<String> backupCodes) {
    }

    @Transactional(readOnly = true)
    // currentToken: 요청한 브라우저의 신뢰 기기 쿠키 (없으면 null)
    public List<TrustedDeviceResponse> getTrustedDevices(Long memberId, String currentToken) {
        return trustedDeviceUseCase.list(memberId, LocalDateTime.now()).stream()
                .map(d -> TrustedDeviceResponse.from(d, trustedDeviceUseCase.isCurrent(d, currentToken)))
                .toList();
    }

    // 해제한 기기가 요청한 브라우저면 true (쿠키도 지우라고)
    @Transactional
    public boolean revokeTrustedDevice(Long memberId, Long deviceId, String currentToken) {
        return trustedDeviceUseCase.revoke(memberId, deviceId, currentToken);
    }

    @Transactional
    public void revokeAllTrustedDevices(Long memberId) {
        trustedDeviceUseCase.revokeAll(memberId);
    }

    @Transactional
    public int deleteExpiredTrustedDevices() {
        return trustedDeviceUseCase.deleteExpired(LocalDateTime.now());
    }

    @Transactional
    public TokenPair refresh(String refreshToken) {
        return memberTokenUseCase.refresh(refreshToken);
    }

    @Transactional
    public void logout(String refreshToken) {
        memberTokenUseCase.revoke(refreshToken);
    }

    @Transactional
    public int deleteExpiredRefreshTokens() {
        return memberTokenUseCase.deleteExpired();
    }


    //임시 조회용
    @Transactional(readOnly = true)
    public MyPageDashboardResponse getMyPageDashboard(Long memberId) {
        return myPageDashboardUseCase.getDashboard(memberId);
    }

    @Transactional(readOnly = true)
    public MemberProfileResponse getProfile(Long memberId) {
        return memberProfileUseCase.getProfile(memberId);
    }

    @Transactional
    public MemberProfileResponse updateProfile(Long memberId, UpdateProfileRequest request) {
        return memberProfileUseCase.updateProfile(memberId, request);
    }

    @Transactional
    public void changePassword(Long memberId, ChangePasswordRequest request) {
        memberChangePasswordUseCase.changePassword(memberId, request);
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminMemberResponse> searchMembersForAdmin(AdminMemberSearchRequest condition, int page, int size) {
        return adminMemberSearchUseCase.search(condition, page, size);
    }
    @Transactional(readOnly = true)
    public AdminMemberDetailResponse getMemberDetailForAdmin(Long memberId) {
        return adminMemberDetailUseCase.get(memberId);
    }

    @Transactional
    public void suspendMember(Long adminId, Long memberId, AdminSuspendRequest request) {
        adminMemberStatusUseCase.suspend(adminId, memberId, request);
    }

    @Transactional
    public void activateMember(Long memberId) {
        adminMemberStatusUseCase.activate(memberId, SuspensionReleaseType.MANUAL);
    }

    @Transactional
    public AdminBulkStatusResponse changeMemberStatusBulk(Long adminId, AdminBulkStatusRequest request) {
        return adminMemberStatusUseCase.bulk(adminId, request);
    }

    @Transactional
    public int releaseExpiredSuspensions() {
        return adminMemberStatusUseCase.releaseExpired();
    }

    @Transactional(readOnly = true)
    public Optional<SellerResponse> getMySeller(Long memberId) {
        return memberSellerUseCase.getMySeller(memberId);
    }

    @Transactional(readOnly = true)
    public boolean isBusinessNumberAvailable(String businessNumber) {
        return memberSellerUseCase.isBusinessNumberAvailable(businessNumber);
    }

    // 외부 호출이 나가므로 DB 트랜잭션 밖에서 실행한다.
    public void verifySellerAccount(Long memberId, SellerAccountVerifyRequest request) {
        memberSellerAccountVerifyUseCase.verify(memberId, request);
    }

    @Transactional
    public MemberSellerUseCase.RegisterResult registerSeller(Long memberId, SellerRegisterRequest request) {
        return memberSellerUseCase.register(memberId, request);
    }

    private static final int SELLER_REPUBLISH_PAGE_SIZE = 500;

    // 모든 판매자의 등록 이벤트를 다시 발행한다 (운영 백필, 내부 API). 한 트랜잭션에 너무 많이 넣지 않게 페이지마다 커밋한다.
    // 중간에 실패해도 받는 쪽이 멱등이라 처음부터 다시 실행하면 된다. 반환: 발행한 건수
    public int republishSellerRegisteredEvents() {
        int total = 0;
        Long afterId = 0L;
        while (true) {
            Long cursor = afterId;
            MemberSellerUseCase.RepublishPage page = transactionTemplate.execute(
                    status -> memberSellerUseCase.republishRegistered(cursor, SELLER_REPUBLISH_PAGE_SIZE));
            total += page.count();
            if (page.count() < SELLER_REPUBLISH_PAGE_SIZE) {
                return total;
            }
            afterId = page.lastId();
        }
    }

    @Transactional(readOnly = true)
    public List<MemberAddressResponse> getAddresses(Long memberId) {
        return memberAddressUseCase.list(memberId);
    }

    @Transactional
    public MemberAddressResponse createAddress(Long memberId, MemberAddressRequest request) {
        return memberAddressUseCase.create(memberId, request);
    }

    @Transactional
    public MemberAddressResponse updateAddress(Long memberId, Long addressId, MemberAddressRequest request) {
        return memberAddressUseCase.update(memberId, addressId, request);
    }

    @Transactional
    public MemberAddressResponse makeDefaultAddress(Long memberId, Long addressId) {
        return memberAddressUseCase.makeDefault(memberId, addressId);
    }

    @Transactional
    public void deleteAddress(Long memberId, Long addressId) {
        memberAddressUseCase.delete(memberId, addressId);
    }

    @Transactional(readOnly = true)
    public AdminDashboardResponse getAdminDashboard() {
        return adminDashboardUseCase.getDashboard();
    }

    // ===== 회원 탈퇴 =====
    // 진행 중 거래 확인은 다른 서비스 HTTP라 트랜잭션 밖에서 한다 (그동안 DB 연결을 잡지 않는다).
    // 상태 변경 · 이벤트 발행 · 새 토큰 발급만 짧은 트랜잭션으로 묶는다.
    public WithdrawalCheckResponse checkWithdrawal(Long memberId) {
        return memberWithdrawalUseCase.check(memberId, LocalDateTime.now());
    }

    // 탈퇴 신청·취소 뒤 상태(탈퇴 대기 표시 wp)가 반영된 새 Access Token을 함께 준다 (다른 서비스 필터는 토큰만 본다)
    // amr: 지금 토큰의 인증 수단 (새 토큰이 이어 받음)
    public WithdrawalResult requestWithdrawal(Long memberId, String password, List<String> amr) {
        memberWithdrawalUseCase.ensureCanRequest(memberId, password);
        LocalDateTime now = LocalDateTime.now();
        String accessToken = transactionTemplate.execute(status -> {
            memberWithdrawalUseCase.request(memberId, now);
            return reissueAccessToken(memberId, amr);
        });
        return new WithdrawalResult(memberWithdrawalUseCase.check(memberId, now), accessToken);
    }

    public WithdrawalResult cancelWithdrawal(Long memberId, List<String> amr) {
        String accessToken = transactionTemplate.execute(status -> {
            memberWithdrawalUseCase.cancel(memberId);
            return reissueAccessToken(memberId, amr);
        });
        memberWithdrawalUseCase.clearPendingMark(memberId); // 커밋 뒤 (Redis)
        return new WithdrawalResult(memberWithdrawalUseCase.check(memberId, LocalDateTime.now()), accessToken);
    }

    public record WithdrawalResult(WithdrawalCheckResponse check, String accessToken) {
    }

    /**
     * 유예 기간이 끝난 탈퇴 대기 회원을 확정한다 (매일 새벽 스케줄러).
     * 회원마다: 진행 중 거래·예치금 재검사(트랜잭션 밖 HTTP) → 막히면 건너뜀 → 확정·개인정보 삭제(짧은 트랜잭션) → 커밋 뒤 완료 메일.
     * 한 명이 실패해도 다음 회원으로 넘어가고, 건너뛰거나 실패한 회원은 다음 실행에서 다시 본다.
     */
    public WithdrawalFinalizeResult finalizeDueWithdrawals(LocalDateTime now) {
        int finalized = 0;
        int blocked = 0;
        int failed = 0;
        Long afterId = 0L;
        while (true) {
            List<Long> ids = memberWithdrawalFinalizeUseCase.findDueIds(now, afterId);
            for (Long memberId : ids) {
                try {
                    List<WithdrawalCheckResponse.WithdrawalBlocker> blockers = memberWithdrawalUseCase.blockersOf(memberId);
                    if (!blockers.isEmpty()) {
                        blocked++;
                        log.atWarn().addKeyValue("event", "member.withdrawal.finalize-blocked")
                                .addKeyValue("memberId", memberId)
                                .log("탈퇴 확정 보류(진행 중 거래·예치금): memberId={}, 이유={}", memberId,
                                        blockers.stream().map(WithdrawalCheckResponse.WithdrawalBlocker::code).toList());
                        continue;
                    }
                    if (Boolean.TRUE.equals(transactionTemplate.execute(
                            status -> memberWithdrawalFinalizeUseCase.finalizeOne(memberId, now)))) {
                        finalized++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.atError().addKeyValue("event", "member.withdrawal.finalize-failed")
                            .addKeyValue("memberId", memberId)
                            .setCause(e)
                            .log("탈퇴 확정 실패 → 다음 실행에서 다시 시도: memberId={}", memberId);
                }
            }
            if (ids.size() < MemberWithdrawalFinalizeUseCase.BATCH_SIZE) {
                return new WithdrawalFinalizeResult(finalized, blocked, failed);
            }
            afterId = ids.get(ids.size() - 1);
        }
    }

    public record WithdrawalFinalizeResult(int finalized, int blocked, int failed) {
    }

    // 다른 서비스가 정리할 탈퇴 확정 회원: 확정 시각 [from, to). from이 없으면 처음부터
    @Transactional(readOnly = true)
    public List<Long> findWithdrawnMemberIds(LocalDateTime from, LocalDateTime to) {
        return memberWithdrawalFinalizeUseCase.findWithdrawnIds(from, to);
    }

    // ===== 탈퇴 대기 Redis 표시 동기화 (WithdrawalPendingSync) =====
    @Transactional(readOnly = true)
    public boolean isWithdrawalPending(Long memberId) {
        return memberSupport.findById(memberId)
                .map(member -> member.getStatus() == MemberStatus.WITHDRAWAL_PENDING)
                .orElse(false);
    }

    private String reissueAccessToken(Long memberId, List<String> amr) {
        Member member = memberSupport.findById(memberId)
                .orElseThrow(() -> new ServiceException(MemberErrorCode.MEMBER_NOT_FOUND));
        return memberTokenUseCase.reissueAccessToken(member, amr);
    }

    // ===== 정지 회원 Redis 키 동기화 (SuspendedMemberSync) =====
    @Transactional(readOnly = true)
    public boolean isSuspended(Long memberId) {
        return memberSupport.findById(memberId)
                .map(member -> member.getStatus() == MemberStatus.SUSPENDED)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<Long> findSuspendedMemberIds() {
        return memberSupport.findIdsByStatus(MemberStatus.SUSPENDED);
    }
}