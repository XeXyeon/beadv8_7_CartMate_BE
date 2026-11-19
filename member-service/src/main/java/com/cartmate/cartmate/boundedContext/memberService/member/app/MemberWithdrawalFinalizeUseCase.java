package com.cartmate.cartmate.boundedContext.memberService.member.app;

import com.cartmate.cartmate.boundedContext.memberService.member.domain.Member;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.MemberStatus;
import com.cartmate.cartmate.boundedContext.memberService.member.out.MemberAddressRepository;
import com.cartmate.cartmate.boundedContext.memberService.member.out.MemberBackupCodeRepository;
import com.cartmate.cartmate.boundedContext.memberService.member.out.MemberOtpRepository;
import com.cartmate.cartmate.boundedContext.memberService.member.out.MemberRepository;
import com.cartmate.cartmate.boundedContext.memberService.member.out.RefreshTokenRepository;
import com.cartmate.cartmate.boundedContext.memberService.member.out.SellerRepository;
import com.cartmate.cartmate.boundedContext.memberService.member.out.TrustedDeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 회원 탈퇴 확정 (3단계). 유예 기간이 끝난 탈퇴 대기 회원의 개인정보를 지운다.
 *
 * <ul>
 *   <li>회원 행은 남긴다 (주문·정산이 member_id로 가리킴). 이메일·비밀번호·닉네임·전화·생년월일을 덮어쓴다 → 같은 이메일로 재가입 가능</li>
 *   <li>판매자 행도 남기고 사업자·연락처·정산 계좌를 지운다</li>
 *   <li>배송지·Refresh Token·신뢰 기기·OTP·백업 코드는 삭제. 정지 이력(관리자 처리 기록)은 남긴다</li>
 *   <li>다른 서비스(product 댓글·리뷰·판매글)는 내부 API(/internal/members/withdrawn)로 확정 회원을 받아 가 정리한다</li>
 * </ul>
 * 진행 중 거래 재검사는 다른 서비스 HTTP라 Facade가 트랜잭션 밖에서 먼저 한다.
 */
@Service
@RequiredArgsConstructor
class MemberWithdrawalFinalizeUseCase {

    static final int BATCH_SIZE = 100;

    private final MemberRepository memberRepository;
    private final SellerRepository sellerRepository;
    private final MemberAddressRepository memberAddressRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TrustedDeviceRepository trustedDeviceRepository;
    private final MemberOtpRepository memberOtpRepository;
    private final MemberBackupCodeRepository memberBackupCodeRepository;
    private final SecurityAlertMailer mailer;

    // 확정 대상 id (afterId 다음부터 BATCH_SIZE개)
    List<Long> findDueIds(LocalDateTime now, Long afterId) {
        return memberRepository.findIdsDueForWithdrawal(MemberStatus.WITHDRAWAL_PENDING, dueBefore(now), afterId,
                Limit.of(BATCH_SIZE));
    }

    /**
     * 트랜잭션 안에서 부른다. 확정했으면 true.
     * 조건부 UPDATE가 0건이면(그사이 취소됐거나 이미 확정) 아무것도 지우지 않는다.
     */
    boolean finalizeOne(Long memberId, LocalDateTime now) {
        Optional<Member> found = memberRepository.findById(memberId);
        if (found.isEmpty()) {
            return false;
        }
        String email = found.get().getEmail(); // 덮어쓰기 전에 잡아 둔다 (완료 메일)

        int updated = memberRepository.finalizeWithdrawal(memberId, MemberStatus.WITHDRAWAL_PENDING, MemberStatus.WITHDRAWN,
                dueBefore(now), now,
                Member.withdrawnEmail(memberId), Member.WITHDRAWN_PASSWORD, Member.withdrawnNickname(memberId),
                Member.WITHDRAWN_PHONE, Member.WITHDRAWN_BIRTH_DATE);
        if (updated == 0) {
            return false;
        }

        sellerRepository.anonymizeByMemberId(memberId);
        memberAddressRepository.deleteAllOf(memberId);
        refreshTokenRepository.deleteAllByMemberId(memberId);
        trustedDeviceRepository.deleteAllByMemberId(memberId);
        memberOtpRepository.deleteAllOf(memberId);
        memberBackupCodeRepository.deleteAllOf(memberId);

        // 커밋된 뒤에만, 다른 스레드에서 보낸다 (롤백되면 보내지 않음)
        mailer.sendAfterCommit(memberId, email, "[CartMate] 회원 탈퇴가 완료되었습니다",
                "회원 탈퇴가 완료되어 계정 정보가 삭제되었습니다.\n"
                        + "관련 법령에 따라 보관해야 하는 주문·결제 기록은 정해진 기간 동안 보관 후 파기됩니다.\n"
                        + "그동안 CartMate를 이용해 주셔서 감사합니다.");
        return true;
    }

    // 확정 시각이 [from, to)인 회원 (from이 없으면 처음부터)
    List<Long> findWithdrawnIds(LocalDateTime from, LocalDateTime to) {
        return from == null
                ? memberRepository.findWithdrawnIdsBefore(MemberStatus.WITHDRAWN, to)
                : memberRepository.findWithdrawnIdsBetween(MemberStatus.WITHDRAWN, from, to);
    }

    // 이 시각 이전에 신청했으면 유예 기간이 끝났다
    private static LocalDateTime dueBefore(LocalDateTime now) {
        return now.minusDays(MemberWithdrawalUseCase.GRACE_DAYS);
    }
}
