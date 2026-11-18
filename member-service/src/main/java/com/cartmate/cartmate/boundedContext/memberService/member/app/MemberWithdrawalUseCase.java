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
import com.cartmate.cartmate.boundedContext.memberService.member.out.WithdrawalTradeClient;
import com.cartmate.cartmate.boundedContext.memberService.member.out.WithdrawalTradeClient.BuyerTrades;
import com.cartmate.cartmate.boundedContext.memberService.member.out.WithdrawalTradeClient.SellerTrades;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.shared.member.event.MemberWithdrawalRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 회원 탈퇴 신청·취소 (1단계).
 *
 * <p>진행 중인 거래가 있으면 신청할 수 없다. 신청하면 탈퇴 대기가 되고, 유예 기간({@link #GRACE_DAYS}일) 동안
 * 로그인·조회·탈퇴 취소·예치금 환급 신청만 할 수 있다. 유예 기간이 지나면 탈퇴가 확정된다 (3단계).
 */
@Service
@RequiredArgsConstructor
class MemberWithdrawalUseCase {

    static final int GRACE_DAYS = 30;

    private final MemberRepository memberRepository;
    private final SellerRepository sellerRepository;
    private final WithdrawalTradeClient withdrawalTradeClient;
    private final PasswordEncoder passwordEncoder;
    private final DomainEventPublisher domainEventPublisher;

    // 트랜잭션 없이 부른다: 진행 중 거래 확인이 다른 서비스 HTTP라 DB 연결을 잡지 않는다 (조회는 저장소가 각자 연다)
    WithdrawalCheckResponse check(Long memberId) {
        Member member = findMember(memberId);
        if (member.getStatus() == MemberStatus.WITHDRAWAL_PENDING) {
            return new WithdrawalCheckResponse(false, List.of(), member.getStatus().name(),
                    member.getWithdrawalRequestedAt(), member.getWithdrawalRequestedAt().plusDays(GRACE_DAYS));
        }
        List<WithdrawalBlocker> blockers = findBlockers(member);
        boolean canWithdraw = member.getStatus() == MemberStatus.ACTIVE && blockers.isEmpty();
        return new WithdrawalCheckResponse(canWithdraw, blockers, member.getStatus().name(), null, null);
    }

    // 트랜잭션 밖에서 부른다: 비밀번호와 진행 중 거래(다른 서비스 HTTP)를 확인한다. 막히면 예외.
    void ensureCanRequest(Long memberId, String password) {
        Member member = findMember(memberId);
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

        // 다른 서비스: Outbox → product 판매글 판매 중지. 회원 안 차단은 새 토큰의 탈퇴 대기 표시(wp)가 맡는다
        domainEventPublisher.publish(new MemberWithdrawalRequestedEvent(member.getId(), sellerIdOf(member), now));
    }

    // 탈퇴 취소. 판매 중지한 판매글은 판매자가 직접 재개하므로 다른 서비스에 알리지 않는다
    void cancel(Long memberId) {
        Member member = findMember(memberId);
        member.cancelWithdrawal();
        memberRepository.save(member);
    }

    // 구매자로서 + (판매자면) 판매자로서 진행 중인 거래를 모은다
    private List<WithdrawalBlocker> findBlockers(Member member) {
        List<WithdrawalBlocker> blockers = new ArrayList<>();

        BuyerTrades buyer = withdrawalTradeClient.getBuyerTrades(member.getId());
        addIf(blockers, "PAYMENT_PENDING_ORDERS", "결제 대기 중인 주문이 있습니다.", buyer.paymentPendingOrders());
        addIf(blockers, "UNCONFIRMED_ORDERS", "구매확정 전인 주문이 있습니다. 구매확정 후 탈퇴할 수 있습니다.", buyer.unconfirmedDeliveries());
        addIf(blockers, "PAYING_SHARED_ORDERS", "분담금 결제 중인 공동 주문이 있습니다.", buyer.payingSharedOrders());
        addIf(blockers, "CLAIMS_IN_PROGRESS", "처리 중인 교환·반품 요청이 있습니다.", buyer.claimsInProgress());
        addIf(blockers, "ACTIVE_GROUP_PURCHASES", "모집 중인 공동구매에 참여하고 있습니다.", buyer.activeGroupPurchases());

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