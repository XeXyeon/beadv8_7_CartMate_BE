package com.cartmate.cartmate.boundedContext.orderService.order.domain.itemcancel;

/**
 * 일부 취소 때 쿠폰 할인을 어떻게 나눌지.
 * 구매자가 취소하면 남은 상품으로 다시 계산하고, 판매자 탓 취소면 구매자 할인을 빼앗지 않도록 금액 비율로 나눈다.
 */
public enum CouponMode {
    RECALCULATE,
    PROPORTIONAL
}
