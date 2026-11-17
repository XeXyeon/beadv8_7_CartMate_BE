package com.cartmate.cartmate.shared.order.event;

import com.cartmate.cartmate.global.event.DomainEvent;

/**
 * 반품 접수 검사 결과. order가 발행하고 delivery가 받아 클레임을 접수하거나 접수 불가로 끝낸다.
 *
 * @param rejectCode accepted가 false일 때 이유(예: 쿠폰 최소 금액)
 */
public record OrderClaimReturnVerifiedEvent(
        Long claimId,
        boolean accepted,
        String rejectCode
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
