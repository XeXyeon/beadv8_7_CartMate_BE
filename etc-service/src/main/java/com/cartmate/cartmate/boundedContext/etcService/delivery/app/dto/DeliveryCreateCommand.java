package com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto;

import com.cartmate.cartmate.shared.delivery.PurchaseType;

import java.util.List;

public record DeliveryCreateCommand(
        Long memberId,
        Long sellerId,
        Long orderId,
        String orderNumber,     // 사용자에게 보여 주는 주문번호. 주문번호가 없는 주문(공유 공동구매)은 null
        PurchaseType purchaseType,
        String receiverName,
        String receiverPhone,
        String zipCode,
        String address,
        String addressDetail,
        String idempotencyKey,
        List<Item> deliveryItem
) {
    public record Item(
            Long orderItemId,
            String productName,
            Long unitPrice,     // 옵션 가격이 반영된 최종 단가
            Long quantity,
            List<Option> options,
            Long sellerDiscountAmount,   // 이 상품 줄의 판매자 부담 쿠폰 할인(줄 전체 수량 기준). 없으면 null
            Long platformDiscountAmount  // 이 상품 줄의 플랫폼 부담 쿠폰 할인(줄 전체 수량 기준). 없으면 null
    ) {
        public Item(Long orderItemId, String productName, Long unitPrice, Long quantity, List<Option> options) {
            this(orderItemId, productName, unitPrice, quantity, options, null, null);
        }
    }

    public record Option(
            String optionName,
            String optionValue
    ) {}
}
