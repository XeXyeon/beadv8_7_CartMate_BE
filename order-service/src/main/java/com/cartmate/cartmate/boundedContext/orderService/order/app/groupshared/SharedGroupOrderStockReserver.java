package com.cartmate.cartmate.boundedContext.orderService.order.app.groupshared;

import com.cartmate.cartmate.boundedContext.orderService.order.domain.groupshared.SharedGroupOrder;
import com.cartmate.cartmate.boundedContext.orderService.order.out.OrderInventoryClient;
import com.cartmate.cartmate.shared.order.OrderType;
import com.cartmate.cartmate.shared.product.dto.InventoryReserveRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공동 주문의 재고 선점 단계. 첫 분담금 결제 직전에 공동 주문의 상품 전체를 한 번 선점하고 "선점함"으로 표시한다.
 *
 * <p>분담금 결제와 다른 트랜잭션(REQUIRES_NEW)으로 먼저 커밋한다. 재고 선점은 상품 서버에 HTTP로 커밋되므로,
 * 표시도 같이 커밋해야 결제가 실패해 결제 트랜잭션이 롤백돼도 주문 서버와 상품 서버가 둘 다 "선점함"으로 맞는다.
 * 선점은 공동 주문이 확정되면 판매 확정, 취소되면 선점 해제된다(상품 서버가 공동 주문 이벤트로 처리).
 *
 * <p>공동 주문을 잠근 뒤 이미 선점했으면 아무것도 하지 않는다(두 참여자가 동시에 첫 결제를 해도 한 번만 선점).
 * 재고가 부족하면 ORDER_OUT_OF_STOCK — 표시하지 않고 롤백되며, 공동 주문 취소는 호출한 쪽이 한다.
 */
@Component
@RequiredArgsConstructor
public class SharedGroupOrderStockReserver {

    private final SharedGroupOrderSupport sharedGroupOrderSupport;
    private final OrderInventoryClient orderInventoryClient;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reserve(Long sharedGroupOrderId) {
        SharedGroupOrder order = sharedGroupOrderSupport.getOrderForUpdate(sharedGroupOrderId);
        if (!order.needsStockReservation()) {
            return;
        }
        orderInventoryClient.reserve(OrderType.SHARED_GROUP, sharedGroupOrderId, order.getItems().stream()
                .map(item -> new InventoryReserveRequest.Item(item.getInventoryId(), item.getQuantity()))
                .toList());
        order.markStockReserved();
    }
}
