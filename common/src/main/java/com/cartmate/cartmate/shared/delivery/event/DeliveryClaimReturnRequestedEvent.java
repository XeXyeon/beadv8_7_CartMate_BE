package com.cartmate.cartmate.shared.delivery.event;

import com.cartmate.cartmate.global.event.DomainEvent;
import com.cartmate.cartmate.shared.delivery.PurchaseType;

import java.util.List;

/**
 * 구매자 사정 반품이 접수될 때 delivery가 발행한다. order가 예상 환불을 계산해 접수해도 되는지 답한다.
 * 쿠폰 최소 금액이 깨져 환불이 음수가 되는 반품은 받지 않기 위해서다.
 *
 * @param orderItemIds   전부 승인된다고 볼 때 반품되는 주문 항목. 상품 한 줄을 수량 전부 반품한다.
 * @param pendingReturns 같은 주문에서 이미 처리 중인 다른 반품(클레임 번호 순서). 이 반품들이 먼저 환불된다고 보고 계산한다.
 */
public record DeliveryClaimReturnRequestedEvent(
        Long claimId,
        Long orderId,
        PurchaseType purchaseType,
        Long buyerMemberId,
        List<Long> orderItemIds,
        List<PendingReturn> pendingReturns
) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "DeliveryClaim";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(claimId);
    }
}
