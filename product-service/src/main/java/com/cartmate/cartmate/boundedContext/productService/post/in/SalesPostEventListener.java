package com.cartmate.cartmate.boundedContext.productService.post.in;

import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostFacade;
import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostPurchaseRecordUseCase.PurchasedItem;
import com.cartmate.cartmate.global.event.OutboxMessageRelayed;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import com.cartmate.cartmate.shared.delivery.event.DeliveryConfirmedEvent;
import com.cartmate.cartmate.shared.member.event.SellerRegisteredEvent;
import com.cartmate.cartmate.shared.order.event.OrderPaidEvent;
import com.cartmate.cartmate.shared.order.event.SharedGroupOrderConfirmedEvent;
import com.cartmate.cartmate.shared.product.event.ProductDeletedEvent;
import com.cartmate.cartmate.shared.product.event.ProductsDeletedByPostEvent;
import com.cartmate.cartmate.shared.trust.event.SellerTrustScoreChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;
import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;
import static org.springframework.transaction.event.TransactionPhase.BEFORE_COMMIT;

@Slf4j
@Component
@RequiredArgsConstructor
public class SalesPostEventListener {
    private final SalesPostFacade salesPostFacade;

    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(propagation = REQUIRES_NEW)
    public void handle(SellerRegisteredEvent event) {
        salesPostFacade.syncSeller(event);
    }

    // product가 보낸 삭제 이벤트. outbox 릴레이로 받는다
    // Kafka가 켜져 있으면 Kafka로 온 것만, 꺼져 있으면 같은 앱 안에서 온 것만 받는다(같은 이벤트를 두 번 처리하지 않게)
    @TransactionalEventListener(fallbackExecution = true, condition = "@relayedEventRouting.accepts(#message)")
    public void handle(OutboxMessageRelayed message) {
        // 판매글 삭제로 상품 삭제가 끝났을 때
        if (message.event() instanceof ProductsDeletedByPostEvent event) {
            salesPostFacade.completeDeleteByEvent(message.envelope().eventId(), event);
        }
        // 판매자가 상품 하나를 삭제했을 때
        if (message.event() instanceof ProductDeletedEvent event) {
            salesPostFacade.deleteProductByEvent(message.envelope().eventId(), event);
        }
    }

    // 신뢰도 점수가 커밋된 뒤 판매글에 보여줄 판매자 신뢰도를 복제한다. 복제가 실패해도 점수 계산은 롤백되지 않는다.
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(propagation = REQUIRES_NEW)
    public void handle(SellerTrustScoreChangedEvent event) {
        salesPostFacade.syncSellerTrust(event);
    }

    // ===== 리뷰 자격용 구매 기록 =====
    // 결제 완료 · 구매확정은 재고 판매 확정 · 페이백 반영과 같은 트랜잭션(Kafka 인박스 처리)에서 받는다.
    // 리뷰는 부가 기능이라 여기서 실패해도 그쪽을 롤백시키지 않도록 예외를 밖으로 던지지 않는다
    // (구매 기록 저장은 별도 트랜잭션이라, 실패하면 그 주문항목의 구매 기록이 빠진다 — 로그로 찾는다).

    // 결제 완료: 개인 주문(일반 · 개인물품 공동구매) → 구매 유형 INDIVIDUAL_GROUP, 구매자 = memberId
    @TransactionalEventListener(phase = BEFORE_COMMIT, fallbackExecution = true)
    public void handle(OrderPaidEvent event) {
        runSafely("결제 완료", event.orderId(), () -> salesPostFacade.recordPurchases(
                PurchaseType.INDIVIDUAL_GROUP, event.orderId(), event.memberId(),
                event.items().stream()
                        .map(item -> new PurchasedItem(item.orderItemId(), item.productId(), item.inventoryId(),
                                item.sellerId(), item.productName(), item.optionName()))
                        .toList()));
    }

    // 공동 주문 확정 → 구매 유형 SHARED_GROUP, 구매자 = 주문을 만든 회원
    @TransactionalEventListener(phase = BEFORE_COMMIT, fallbackExecution = true)
    public void handle(SharedGroupOrderConfirmedEvent event) {
        runSafely("공동 주문 확정", event.sharedGroupOrderId(), () -> salesPostFacade.recordPurchases(
                PurchaseType.SHARED_GROUP, event.sharedGroupOrderId(), event.creatorMemberId(),
                event.items().stream()
                        .map(item -> new PurchasedItem(item.orderItemId(), item.productId(), item.inventoryId(),
                                item.sellerId(), item.productName(), item.optionName()))
                        .toList()));
    }

    // 구매확정 → 이벤트에 온 주문항목만 확정 (구매확정 전 전액 환불된 상품은 오지 않는다)
    @TransactionalEventListener(phase = BEFORE_COMMIT, fallbackExecution = true)
    public void handle(DeliveryConfirmedEvent event) {
        runSafely("구매확정", event.orderId(), () -> salesPostFacade.confirmPurchases(
                event.purchaseType(), event.orderId(),
                event.items().stream().map(DeliveryConfirmedEvent.Item::orderItemId).toList(),
                event.confirmedAt()));
    }

    // 두 이벤트가 동시에 같은 주문항목의 줄을 처음 만들면 유니크 키 충돌이 날 수 있다 → 한 번 더 하면 있는 줄을 채운다
    private void runSafely(String eventName, Long orderId, Runnable work) {
        try {
            try {
                work.run();
            } catch (DataIntegrityViolationException e) {
                work.run();
            }
        } catch (Exception e) {
            log.error("리뷰 구매 기록 저장 실패({}): order #{} — 이 주문의 리뷰 자격이 빠졌을 수 있음", eventName, orderId, e);
        }
    }
}
