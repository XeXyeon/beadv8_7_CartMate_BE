package com.cartmate.cartmate.boundedContext.orderService.order.domain.buyercancel;

// 요청한 항목 한 줄의 판정 결과. 결과가 아직 없으면 항목의 결과 칸은 비어 있다
public enum BuyerCancelItemResult {
    CANCELED,
    REJECTED
}
