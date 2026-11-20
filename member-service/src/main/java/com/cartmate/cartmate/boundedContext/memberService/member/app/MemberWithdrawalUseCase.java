package com.cartmate.cartmate.boundedContext.memberService.member.app;

import com.cartmate.cartmate.boundedContext.memberService.member.exception.MemberErrorCode;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.Member;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.MemberRole;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.MemberStatus;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.Seller;
import com.cartmate.cartmate.boundedContext.memberService.member.dto.WithdrawalCheckResponse;
import com.cartmate.cartmate.boundedContext.memberService.member.dto.WithdrawalCheckResponse.WithdrawalBlocker;
import com.cartmate.cartmate.boundedContext.memberService.member.out.MemberRepository;
import com.cartmate.cartmate.boundedContext.memberService.member.out.SellerRepository;
import com.cartmate.cartmate.boundedContext.memberService.member.out.WithdrawalPendingStore;
import com.cartmate.cartmate.boundedContext.memberService.member.out.WithdrawalTradeClient;
import com.cartmate.cartmate.boundedContext.memberService.member.out.WithdrawalTradeClient.BuyerTrades;
import com.cartmate.cartmate.boundedContext.memberService.member.out.WithdrawalTradeClient.SellerTrades;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.shared.member.event.MemberWithdrawalRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 회원 탈퇴 신청·취소·조회 (1단계). 확정은 {@link MemberWithdrawalFinalizeUseCase}.
 *
 * <p>진행 중인 거래나 예치금이 있으면 신청할 수 없다. 신청하면 탈퇴 대기가 되고, 유예 기간({@link #GRACE_DAYS}일) 동안
 * 로그인·조회·탈퇴 취소·예치금 환급 신청만 할 수 있다. 유예 기간이 지나면 매일 새벽 스케줄러가 확정한다 (확정 직전에 다시 검사).
 */
@Slf4j
@Service
@RequiredArgsConstructor
class MemberWithdrawalUseCase {

    static final int GRACE_DAYS = 30;

    private final MemberRepository memberRepository;
    private final SellerRepository sellerRepository;
    private final WithdrawalTradeClient withdrawalTradeClient;
    private final PasswordEncoder passwordEncoder;
    private final DomainEventPublisher domainEventPublisher;
    private final WithdrawalPendingStore withdrawalPendingStore;

    // 트랜잭션 없이 부른다: 진행 중 거래 확인이 다른 서비스 HTTP라 DB 연결을 잡지 않는다 (조회는 저장소가 각자 연다)
    WithdrawalCheckResponse check(Long memberId, LocalDateTime now) {
        Member member = findMember(memberId);
        if (member.getStatus() == MemberStatus.WITHDRAWAL_PENDING) {
            LocalDateTime scheduledAt = member.getWithdrawalRequestedAt().plusDays(GRACE_DAYS);
            // 확정 예정일이 지났는데 아직 대기면 확정이 막혔을 수 있다 → 그때만 이유를 계산해 보여 준다 (그 전엔 HTTP 없이)
            List<WithdrawalBlocker> blockers = now.isBefore(scheduledAt) ? List.of() : findBlockers(member);
            return new WithdrawalCheckResponse(false, blockers, member.getStatus().name(),
                    member.getWithdrawalRequestedAt(), scheduledAt);
        }
        List<WithdrawalBlocker> blockers = findBlockers(member);
        boolean canWithdraw = member.getStatus() == MemberStatus.ACTIVE && member.getRole() != MemberRole.ADMIN
                && blockers.isEmpty();
        return new WithdrawalCheckResponse(canWithdraw, blockers, member.getStatus().name(), null, null);
    }

    // 트랜잭션 밖에서 부른다: 상태·비밀번호와 진행 중 거래(다른 서비스 HTTP)를 확인한다. 막히면 예외.
    void ensureCanRequest(Long memberId, String password) {
        Member member = findMember(memberId);
        member.validateCanRequestWithdrawal(); // 관리자·이미 신청 등은 HTTP 전에 거른다
        if (!passwordEncoder.matches(password, member.getPassword())) {
            throw new ServiceException(MemberErrorCode.PASSWORD_MISMATCH);
        }

        List<WithdrawalBlocker> blockers = findBlockers(member);
        if (!blockers.isEmpty()) {
            throw new ServiceException(MemberErrorCode.MEMBER_WITHDRAWAL_BLOCKED.resultCode(), blockers.get(0).message());
        }
    }

    // 트랜잭션 안에서 부른다: 상태 변경과 이벤트 발행만. 확인 이후 상태가 바뀌었으면 requestWithdrawal이 막는다.
    void request(Long memberId, LocalDateTime now) {
        Member member = findMember(memberId);
        member.requestWithdrawal(now);
        memberRepository.save(member);

        // 다른 서비스: Outbox → product 판매글 판매 중지.
        // 회원 서비스 안: 같은 이벤트 릴레이로 Redis 표시(WithdrawalPendingSync) → 신청 전에 받은 토큰(다른 기기)도 바로 막힌다
        domainEventPublisher.publish(new MemberWithdrawalRequestedEvent(member.getId(), sellerIdOf(member), now));
    }

    // 탈퇴 취소: 조건부 UPDATE라 확정 스케줄러와 동시에 와도 둘 중 하나만 성공한다.
    // 판매 중지한 판매글은 판매자가 직접 재개하므로 다른 서비스에 알리지 않는다
    void cancel(Long memberId) {
        int updated = memberRepository.cancelWithdrawal(memberId, MemberStatus.WITHDRAWAL_PENDING, MemberStatus.ACTIVE);
        if (updated == 1) {
            return;
        }
        MemberStatus status = findMember(memberId).getStatus();
        throw new ServiceException(status == MemberStatus.WITHDRAWN
                ? MemberErrorCode.MEMBER_WITHDRAWAL_ALREADY_FINALIZED
                : MemberErrorCode.MEMBER_WITHDRAWAL_NOT_REQUESTED);
    }

    // 취소 커밋 뒤에 부른다: 신청 때 둔 Redis 표시를 지운다. 지우지 못해도 취소는 되돌리지 않는다
    // (표시는 Access Token 수명 + 여유가 지나면 저절로 사라진다. 그동안 wp 없는 새 토큰의 변경 요청이 막힐 수 있다)
    void clearPendingMark(Long memberId) {
        try {
            withdrawalPendingStore.clear(memberId);
        } catch (RuntimeException e) {
            log.atWarn().addKeyValue("event", "member.withdrawal.pending-mark-clear-failed")
                    .addKeyValue("memberId", memberId)
                    .setCause(e)
                    .log("탈퇴 취소 후 Redis 탈퇴 대기 표시를 지우지 못함 → 표시가 만료될 때까지 변경 요청이 막힐 수 있다: memberId={}", memberId);
        }
    }

    // 확정 직전 재검사 (트랜잭션 밖, 다른 서비스 HTTP)
    List<WithdrawalBlocker> blockersOf(Long memberId) {
        return findBlockers(findMember(memberId));
    }

    // 구매자로서 + (판매자면) 판매자로서 진행 중인 거래와 예치금을 모은다
    private List<WithdrawalBlocker> findBlockers(Member member) {
        List<WithdrawalBlocker> blockers = new ArrayList<>();

        BuyerTrades buyer = withdrawalTradeClient.getBuyerTrades(member.getId());
        addIf(blockers, "PAYMENT_PENDING_ORDERS", "결제 대기 중인 주문이 있습니다.", buyer.paymentPendingOrders());
        addIf(blockers, "UNCONFIRMED_ORDERS", "구매확정 전인 주문이 있습니다. 구매확정 후 탈퇴할 수 있습니다.", buyer.unconfirmedDeliveries());
        addIf(blockers, "PAYING_SHARED_ORDERS", "분담금 결제 중인 공동 주문이 있습니다.", buyer.payingSharedOrders());
        addIf(blockers, "CLAIMS_IN_PROGRESS", "처리 중인 교환·반품 요청이 있습니다.", buyer.claimsInProgress());
        addIf(blockers, "ACTIVE_GROUP_PURCHASES", "모집 중인 공동구매에 참여하고 있습니다.", buyer.activeGroupPurchases());
        // count에는 잔액(원)을 담는다
        addIf(blockers, "WALLET_BALANCE_REMAINING",
                "예치금 %,d원이 남아 있습니다. 환급받은 뒤 탈퇴할 수 있습니다.".formatted(buyer.walletBalance()), buyer.walletBalance());
        // 출금은 신청할 때 잔액에서 빠지지만, 실패·취소되면 잔액으로 돌아온다 → 끝날 때까지 기다린다 (count에는 금액)
        addIf(blockers, "WALLET_WITHDRAW_IN_PROGRESS",
                "예치금 출금 %,d원이 처리 중입니다. 출금이 끝난 뒤 탈퇴할 수 있습니다.".formatted(buyer.withdrawingAmount()),
                buyer.withdrawingAmount());

        Long sellerId = sellerIdOf(member);
        if (sellerId != null) {
            SellerTrades seller = withdrawalTradeClient.getSellerTrades(sellerId);
            addIf(blockers, "SELLER_UNCONFIRMED_SALES", "발송·구매확정 전인 판매 건이 있습니다.", seller.unconfirmedDeliveries());
            addIf(blockers, "SELLER_CLAIMS_IN_PROGRESS", "처리 중인 교환·반품 요청이 있습니다(판매).", seller.claimsInProgress());
            addIf(blockers, "SELLER_PENDING_PAYOUTS", "지급 전인 정산이 있습니다.", seller.pendingPayouts());
        }
        return blockers;
    }

    private static void addIf(List<WithdrawalBlocker> blockers, String code, String message, long count) {
        if (count > 0) {
            blockers.add(new WithdrawalBlocker(code, message, count));
        }
    }

    private Long sellerIdOf(Member member) {
        if (member.getRole() != MemberRole.SELLER) {
            return null;
        }
        return sellerRepository.findByMemberId(member.getId()).map(Seller::getId).orElse(null);
    }

    private Member findMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new ServiceException(MemberErrorCode.MEMBER_NOT_FOUND));
    }
}
