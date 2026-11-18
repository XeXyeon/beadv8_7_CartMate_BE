package com.cartmate.cartmate.boundedContext.productService.post.in;

import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostFacade;
import com.cartmate.cartmate.shared.member.event.MemberWithdrawalRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class SalesPostMemberEventListener {

    private final SalesPostFacade salesPostFacade;

    // 판매자가 탈퇴를 신청하면 판매 중인 판매글을 전부 판매 중지한다 (회원 서비스 → Kafka → Inbox 트랜잭션 안).
    // 판매 중지는 기존 흐름(상품 판매 중지 + 숨김 이벤트)을 그대로 타서, 모집 중 공동구매 정리까지 따라온다.
    // 하나라도 실패하면 전체가 롤백되고 Inbox가 다시 받는다. 판매 중인 글만 고르므로 다시 와도 이미 중지된 글은 건너뛴다(멱등).
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void handle(MemberWithdrawalRequestedEvent event) {
        if (event.sellerId() == null) {
            return;
        }
        for (Long postId : salesPostFacade.findOnSalePostIdsOfSeller(event.sellerId())) {
            salesPostFacade.pauseSalesPostBySeller(postId, event.sellerId(), event.memberId());
        }
    }
}
