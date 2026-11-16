package com.cartmate.cartmate.boundedContext.orderService.order.domain.personal;

/**
 * 구매자가 고른 상품을 취소하면 돌려받을 금액 (배송 서버가 모두 취소한다고 볼 때).
 *
 * @param couponReturned 이번 취소로 쿠폰이 돌아오는지
 */
public record OrderCancelPreview(long refundAmount, boolean couponReturned) {
}
