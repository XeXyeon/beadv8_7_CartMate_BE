package com.cartmate.cartmate.boundedContext.orderService.order.domain.personal;

/**
 * 고른 상품을 반품할 때의 예상 환불.
 *
 * @param refundAmount 돌려줄 금액. 구매자가 더 내야 하는 경우도 0원이다
 * @param underpaid    쿠폰 조건이 깨져 구매자가 오히려 더 내야 하는 반품인지
 */
public record OrderReturnPreview(long refundAmount, boolean underpaid) {
}
