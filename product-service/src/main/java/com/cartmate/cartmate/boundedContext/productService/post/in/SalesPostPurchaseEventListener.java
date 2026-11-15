package com.cartmate.cartmate.boundedContext.productService.post.in;

import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostPurchaseRecordUseCase;
import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostPurchaseRecordUseCase.PurchasedItem;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import com.cartmate.cartmate.shared.delivery.event.DeliveryConfirmedEvent;
import com.cartmate.cartmate.shared.order.event.OrderPaidEvent;
import com.cartmate.cartmate.shared.order.event.SharedGroupOrderConfirmedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 리뷰 자격용 구매 기록을 만드는 이벤트들.
 *
 * <ul>
 *   <li>결제 완료(OrderPaidEvent): 개인 주문(일반 · 개인물품 공동구매) → 구매 유형 INDIVIDUAL_GROUP, 구매자 = memberId</li>
 *   <li>공동 주문 확정(SharedGroupOrderConfirmedEvent) → 구매 유형 SHARED_GROUP, 구매자 = 주문을 만든 회원</li>
 *   <li>구매확정(DeliveryConfirmedEvent) → 이벤트에 온 주문항목만 확정 (구매확정 전 전액 환불된 상품은 오지 않는다)</li>
 * </ul>
 * 결제 완료 · 구매확정은 재고 판매 확정 · 페이백 반영과 같은 트랜잭션에서 받는다.
 * 리뷰는 부가 기능이라 여기서 실패해도 그쪽을 롤백시키지 않도록 예외를 밖으로 던지지 않는다
 * (구매 기록 저장은 별도 트랜잭션이라, 실패하면 그 주문항목의 구매 기록이 빠진다 — 로그로 찾는다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesPostPurchaseEventListener {

    private final SalesPostPurchaseRecordUseCase salesPostPurchaseRecordUseCase;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void handle(OrderPaidEvent event) {
        runSafely("결제 완료", event.orderId(), () -> salesPostPurchaseRecordUseCase.recordPurchases(
                PurchaseType.INDIVIDUAL_GROUP, event.orderId(), event.memberId(),
                event.items().stream()
                        .map(item -> new PurchasedItem(item.orderItemId(), item.productId(), item.inventoryId(),
                                item.sellerId(), item.productName(), item.optionName()))
                        .toList()));
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void handle(SharedGroupOrderConfirmedEvent event) {
        runSafely("공동 주문 확정", event.sharedGroupOrderId(), () -> salesPostPurchaseRecordUseCase.recordPurchases(
                PurchaseType.SHARED_GROUP, event.sharedGroupOrderId(), event.creatorMemberId(),
                event.items().stream()
                        .map(item -> new PurchasedItem(item.orderItemId(), item.productId(), item.inventoryId(),
                                item.sellerId(), item.productName(), item.optionName()))
                        .toList()));
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void handle(DeliveryConfirmedEvent event) {
        runSafely("구매확정", event.orderId(), () -> salesPostPurchaseRecordUseCase.confirmPurchases(
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
