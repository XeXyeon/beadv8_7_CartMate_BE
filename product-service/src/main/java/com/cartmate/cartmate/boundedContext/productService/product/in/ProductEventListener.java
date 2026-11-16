package com.cartmate.cartmate.boundedContext.productService.product.in;

import com.cartmate.cartmate.boundedContext.productService.product.app.ProductFacade;
import com.cartmate.cartmate.global.event.OutboxMessageRelayed;
import com.cartmate.cartmate.shared.order.event.OrderCanceledEvent;
import com.cartmate.cartmate.shared.order.event.OrderPaidEvent;
import com.cartmate.cartmate.shared.order.OrderType;
import com.cartmate.cartmate.shared.order.event.OrderSellerItemsCanceledEvent;
import com.cartmate.cartmate.shared.order.event.SharedGroupOrderCanceledEvent;
import com.cartmate.cartmate.shared.order.event.SharedGroupOrderConfirmedEvent;
import com.cartmate.cartmate.shared.order.event.SharedGroupOrderSellerItemsCanceledEvent;
import com.cartmate.cartmate.shared.post.event.SalesPostDeletedEvent;
import com.cartmate.cartmate.shared.post.event.SalesPostRegisterFailedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;


@Component
@RequiredArgsConstructor
public class ProductEventListener {
    private final ProductFacade productFacade;

    // 판매글 삭제 · 등록 실패. outbox 릴레이로 받는다
    // Kafka가 켜져 있으면 Kafka로 온 것만, 꺼져 있으면 같은 앱 안에서 온 것만 받는다(같은 이벤트를 두 번 처리하지 않게)
    @TransactionalEventListener(fallbackExecution = true, condition = "@relayedEventRouting.accepts(#message)")
    public void handle(OutboxMessageRelayed message) {
        if (message.event() instanceof SalesPostDeletedEvent event) {
            productFacade.deleteProductByPost(message.envelope().eventId(), event.postId());
        } else if (message.event() instanceof SalesPostRegisterFailedEvent event) {
            productFacade.rollbackByApiFail(message.envelope().eventId(), event.postId());
        }
    }

    // 주문 이벤트 → 재고. Kafka 인박스가 이벤트를 꺼내 같은 트랜잭션 안에서 발행한다(처리 완료 표시와 함께 커밋)
    // 결제 완료 → 판매 확정
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void handle(OrderPaidEvent event) {
        productFacade.confirmInventories(event.orderType(), event.orderId());
    }

    // 주문 취소 → 선점 해제(결제 전) 또는 재고 복구(결제 후)
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void handle(OrderCanceledEvent event) {
        productFacade.cancelInventories(event.orderType(), event.orderId());
    }

    // 판매자 몫 취소(결제된 개인 주문) → 그 판매자 재고만 재고 복구. 이 이벤트에는 주문 종류가 없다
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void handle(OrderSellerItemsCanceledEvent event) {
        productFacade.cancelSellerInventories(event.orderId(), event.sellerId());
    }

    // ===== 공동 주문(공동물품 공동구매) → 재고. 선점 기록의 주문 종류는 SHARED_GROUP =====
    // 공동 주문 id는 개인 주문 id와 겹칠 수 있어 반드시 주문 종류를 함께 넘긴다.
    // 선점 기록이 없으면(이 처리가 생기기 전에 만들어진 공동 주문 등) 아무것도 하지 않는다

    // 마지막 분담금 결제로 공동 주문 확정 → 판매 확정
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void handle(SharedGroupOrderConfirmedEvent event) {
        productFacade.confirmInventories(OrderType.SHARED_GROUP, event.sharedGroupOrderId());
    }

    // 공동 주문 취소 → 선점 해제(확정 전) 또는 재고 복구(확정 후)
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void handle(SharedGroupOrderCanceledEvent event) {
        productFacade.cancelInventories(OrderType.SHARED_GROUP, event.sharedGroupOrderId());
    }

    // 확정된 공동 주문의 판매자 몫 취소 → 그 판매자 재고만 재고 복구
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void handle(SharedGroupOrderSellerItemsCanceledEvent event) {
        productFacade.cancelSellerInventories(OrderType.SHARED_GROUP, event.sharedGroupOrderId(), event.sellerId());
    }
}
