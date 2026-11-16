package com.cartmate.cartmate.shared.order.event;

import com.cartmate.cartmate.global.event.DomainEvent;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 구매자가 고른 상품의 취소를 배송 서버에 요청할 때 order가 발행한다.
 * 배송 서버는 requestedAt 기준으로 판매자마다 판정해 {@code DeliveryBuyerCancelResultEvent} 하나로 답한다.
 *
 * <p>응답이 없으면 같은 requestId·requestedAt·items로 다시 보낸다. 받는 쪽은 같은 요청을 여러 번 받아도 같은 결과를 돌려준다.
 */
public record OrderBuyerCancelRequestedEvent(
        Long requestId,
        Long orderId,
        Long buyerMemberId,
        LocalDateTime requestedAt,
        List<Item> items
) implements DomainEvent {

    public record Item(Long orderItemId, Long sellerId) {
    }

    @Override
    public String aggregateType() {
        return "Order";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(orderId);
    }
}
