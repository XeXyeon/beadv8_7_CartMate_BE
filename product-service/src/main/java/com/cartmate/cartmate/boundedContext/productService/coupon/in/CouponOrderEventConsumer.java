package com.cartmate.cartmate.boundedContext.productService.coupon.in;

import com.cartmate.cartmate.boundedContext.productService.coupon.app.CouponFacade;
import com.cartmate.cartmate.shared.order.event.OrderCouponRestoreRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 주문에 쓴 쿠폰 복구. 주문 서버의 복구 요청 이벤트를 받는다 (토픽 cartmate.order.order-coupon-restore-requested).
 * 놓치면 쿠폰이 사용 완료로 남으므로 Inbox 트랜잭션 안에서 처리해 실패하면 다시 받게 한다.
 * 그 주문에 쓴 쿠폰만 돌리고, 이미 돌렸으면 아무것도 하지 않아 같은 이벤트가 두 번 와도 같다.
 */
@Component
@RequiredArgsConstructor
public class CouponOrderEventConsumer {

    private final CouponFacade couponFacade;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void handle(OrderCouponRestoreRequestedEvent event) {
        couponFacade.restoreForOrder(event.memberId(), event.memberCouponId(), event.orderType(), event.orderId());
    }
}
