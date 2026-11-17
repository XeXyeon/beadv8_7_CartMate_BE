package com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto;

import com.cartmate.cartmate.shared.delivery.PurchaseType;

// 배송이 속한 주문. 개인 주문과 공동물품 주문은 주문 ID가 겹칠 수 있어 구매 유형과 함께 쓴다.
public record DeliveryOrderRef(
        Long orderId,
        PurchaseType purchaseType
) {
}
