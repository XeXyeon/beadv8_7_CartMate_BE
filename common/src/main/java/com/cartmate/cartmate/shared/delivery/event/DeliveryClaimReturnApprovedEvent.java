package com.cartmate.cartmate.shared.delivery.event;

import com.cartmate.cartmate.global.event.DomainEvent;
import com.cartmate.cartmate.shared.delivery.PurchaseType;

import java.util.List;

/**
 * 판매자가 반품을 승인할 때 delivery가 발행한다. order가 쿠폰 할인을 반영해 환불 금액을 정하고 payment에 환불을 요청한다.
 * 승인된 상품이 하나도 없으면(모두 거절) 발행하지 않는다.
 *
 * @param purchaseType 개인 주문과 공동물품 주문은 주문 ID가 겹칠 수 있어 구분한다.
 * @param sellerFault  판매자 탓 사유면 할인을 비율로 나누고, 아니면 남은 상품으로 쿠폰을 다시 계산한다.
 * @param orderItemIds 승인된 상품(주문 항목). 상품 한 줄을 수량 전부 반품한다.
 */
public record DeliveryClaimReturnApprovedEvent(
        Long claimId,
        Long deliveryId,
        Long orderId,
        PurchaseType purchaseType,
        Long buyerMemberId,
        Long sellerId,
        boolean sellerFault,
        List<Long> orderItemIds
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
