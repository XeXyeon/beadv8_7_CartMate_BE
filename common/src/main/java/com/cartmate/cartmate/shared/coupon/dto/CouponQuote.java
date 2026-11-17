package com.cartmate.cartmate.shared.coupon.dto;

/**
 * 주문 하나에 쿠폰 한 장을 쓸 때의 할인 결과.
 *
 * @param platformBurden    전체 쿠폰이면 true(플랫폼 부담), 판매자 쿠폰이면 false(판매자 부담)
 * @param sellerId          판매자 쿠폰이면 그 판매자, 전체 쿠폰이면 null
 * @param discountType      "FIXED" 또는 "RATE". 일부 취소 뒤 남은 상품으로 할인을 다시 계산할 때 쓴다
 * @param maxDiscountAmount 정률 쿠폰의 최대 할인. 없으면 null
 * @param minOrderAmount    쿠폰을 쓸 수 있는 최소 금액
 */
public record CouponQuote(
        Long memberCouponId,
        Long couponId,
        String couponName,
        long discountAmount,
        boolean platformBurden,
        Long sellerId,
        String discountType,
        long discountValue,
        Long maxDiscountAmount,
        long minOrderAmount
) {
}
