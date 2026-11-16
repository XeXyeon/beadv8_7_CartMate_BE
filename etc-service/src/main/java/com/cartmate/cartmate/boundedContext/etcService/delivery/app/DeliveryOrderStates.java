package com.cartmate.cartmate.boundedContext.etcService.delivery.app;

import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryOrderState;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryOrderStatus;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryItemCancelMemoRepository;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryOrderStateRepository;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class DeliveryOrderStates {

    private final DeliveryOrderStateRepository deliveryOrderStateRepository;
    private final DeliveryItemCancelMemoRepository deliveryItemCancelMemoRepository;

    public boolean acceptCreation(Long orderId, PurchaseType purchaseType) {
        return !lock(orderId, purchaseType, DeliveryOrderStatus.CREATED).state().isCanceled();
    }

    public Locked lockForCancel(Long orderId, PurchaseType purchaseType) {
        return lock(orderId, purchaseType, DeliveryOrderStatus.CANCELED);
    }

    public Locked lockForSellerCancel(Long orderId, PurchaseType purchaseType) {
        return lock(orderId, purchaseType, DeliveryOrderStatus.CREATED);
    }

    public Set<Long> memoedOrderItemIds(Long orderId, PurchaseType purchaseType) {
        return new HashSet<>(deliveryItemCancelMemoRepository.findOrderItemIds(purchaseType, orderId));
    }

    // 한 요청의 메모는 부르는 쪽 트랜잭션 하나에서 모두 쓴다. 중간에 실패하면 함께 롤백되고 Inbox가 다시 처리한다.
    public void markItemsCanceled(Long orderId, PurchaseType purchaseType, Collection<Long> orderItemIds) {
        LocalDateTime now = LocalDateTime.now();
        orderItemIds.forEach(orderItemId ->
                deliveryItemCancelMemoRepository.insertIfAbsent(purchaseType.name(), orderId, orderItemId, now));
    }

    private Locked lock(Long orderId, PurchaseType purchaseType, DeliveryOrderStatus initial) {
        boolean inserted = deliveryOrderStateRepository.insertIfAbsent(
                orderId, purchaseType.name(), initial.name(), LocalDateTime.now()) == 1;
        DeliveryOrderState state = deliveryOrderStateRepository.findForUpdate(orderId, purchaseType)
                .orElseThrow(() -> new IllegalStateException(
                        "주문 단위 배송 상태를 찾을 수 없다: orderId=" + orderId + ", purchaseType=" + purchaseType));
        return new Locked(state, inserted);
    }

    public record Locked(DeliveryOrderState state, boolean inserted) {
    }
}
