package com.cartmate.cartmate.shared.delivery.event;

import com.cartmate.cartmate.global.event.DomainEvent;
import com.cartmate.cartmate.shared.delivery.PurchaseType;

import java.time.LocalDateTime;
import java.util.List;

// 배송이 구매확정될 때 발행. payout이 정산을 만들 때 사용
public record DeliveryConfirmedEvent(
        Long deliveryId,
        Long sellerId,
        Long orderId,
        PurchaseType purchaseType, // 개인 주문과 공동물품 주문은 주문상품 ID가 겹칠 수 있어 구분한다.
        LocalDateTime orderedAt, // 주문 시각. 배송이 만들어진 시각을 쓴다.
        LocalDateTime confirmedAt,
        List<Item> items
) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "Delivery";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(deliveryId);
    }

    public record Item(
            Long orderItemId,
            String productName, // 주문 시점의 상품명 스냅샷
            Long unitPrice, // 옵션 가격이 반영된 최종 단가
            Long quantity,
            // 정산 대상 수량(quantity)에 해당하는 쿠폰 할인. 환불로 수량이 줄면 그 비율만큼(원 미만 버림) 줄인 값이다.
            // 쿠폰이 없거나 이 칸이 생기기 전 이벤트면 null이고 0으로 본다.
            Long sellerDiscountAmount,
            Long platformDiscountAmount
    ) {
        public Item(Long orderItemId, String productName, Long unitPrice, Long quantity) {
            this(orderItemId, productName, unitPrice, quantity, null, null);
        }
    }
}
