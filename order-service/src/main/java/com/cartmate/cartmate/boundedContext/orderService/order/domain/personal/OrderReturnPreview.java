package com.cartmate.cartmate.boundedContext.orderService.order.domain.personal;

/**
 * 고른 상품을 반품할 때의 예상 환불.
 *
 * @param refundAmount 돌려줄 금액 (반품 상품 금액 - 그 상품에 고정된 쿠폰 할인)
 */
public record OrderReturnPreview(long refundAmount) {
}
