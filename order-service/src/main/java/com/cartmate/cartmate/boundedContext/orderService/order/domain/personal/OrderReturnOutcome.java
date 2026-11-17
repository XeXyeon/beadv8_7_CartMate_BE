package com.cartmate.cartmate.boundedContext.orderService.order.domain.personal;

/**
 * 승인된 반품을 주문에 반영한 결과.
 *
 * @param refundAmount     이번 반품으로 돌려줄 금액. 0원일 수 있다
 * @param couponReturnable 이번 반품으로 쿠폰을 돌려줘야 하는지
 */
public record OrderReturnOutcome(long refundAmount, boolean couponReturnable) {
}
