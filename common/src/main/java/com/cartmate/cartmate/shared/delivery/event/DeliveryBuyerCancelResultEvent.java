package com.cartmate.cartmate.shared.delivery.event;

import com.cartmate.cartmate.global.event.DomainEvent;

import java.util.List;

/**
 * 구매자 취소 요청 하나에 대한 판매자별 판정 결과. 배송 서버가 요청마다 한 번 발행한다.
 *
 * @param results 요청에 담긴 판매자마다 한 줄. canceled가 false면 failureCode에 거절 이유가 있다.
 */
public record DeliveryBuyerCancelResultEvent(
        Long requestId,
        Long orderId,
        List<SellerResult> results
) implements DomainEvent {

    public record SellerResult(Long sellerId, boolean canceled, String failureCode) {
    }

    @Override
    public String aggregateType() {
        return "DeliveryOrder";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(orderId);
    }
}
