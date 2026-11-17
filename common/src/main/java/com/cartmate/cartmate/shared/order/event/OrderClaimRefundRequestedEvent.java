package com.cartmate.cartmate.shared.order.event;

import com.cartmate.cartmate.global.event.DomainEvent;

/**
 * 판매자가 승인한 반품의 환불 금액을 order가 쿠폰 할인을 반영해 정하고 발행한다.
 * payment가 클레임당 한 번 환불하고 환불 결과 이벤트를 낸다.
 *
 * @param amount 환불 금액. 쿠폰 조건 때문에 0원일 수 있고, 그래도 결과 이벤트를 받기 위해 발행한다.
 */
public record OrderClaimRefundRequestedEvent(
        Long claimId,
        Long orderId,
        Long buyerMemberId,
        long amount
) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "Order";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(claimId);
    }
}
