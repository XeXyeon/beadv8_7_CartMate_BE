package com.cartmate.cartmate.boundedContext.orderService.order.domain.personal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 결제 때 쓴 쿠폰의 조건. 구매자가 일부 취소하면 남은 상품으로 할인을 다시 계산할 때 쓴다.
 * 결제 뒤 쿠폰 정책이 바뀌어도 결제 당시 조건으로 계산하도록 주문에 함께 저장한다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderCouponRule {

    @Enumerated(EnumType.STRING)
    @Column(name = "coupon_discount_type", columnDefinition = "varchar(10)")
    private DiscountType discountType;

    @Column(name = "coupon_discount_value")
    private Long discountValue;

    @Column(name = "coupon_max_discount")
    private Long maxDiscount;

    @Column(name = "coupon_min_order_amount")
    private Long minOrderAmount;

    public enum DiscountType {
        FIXED,
        RATE
    }

    private OrderCouponRule(DiscountType discountType, long discountValue, Long maxDiscount, long minOrderAmount) {
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.maxDiscount = maxDiscount;
        this.minOrderAmount = minOrderAmount;
    }

    // 할인 방식이 없는 쿠폰 결과(이 칸이 생기기 전 상품 서버 응답)는 조건 없음으로 본다
    public static OrderCouponRule of(String discountType, long discountValue, Long maxDiscount, long minOrderAmount) {
        if (discountType == null) {
            return null;
        }
        return new OrderCouponRule(DiscountType.valueOf(discountType), discountValue, maxDiscount, minOrderAmount);
    }

    // 상품 서버 Coupon.discountFor와 같은 규칙: 최소 금액 미만이면 0, 정률은 원 미만 버림, 최대 할인까지, 대상 금액을 넘지 않음
    public long discountFor(long eligibleAmount) {
        if (eligibleAmount <= 0 || eligibleAmount < minOrderAmount) {
            return 0;
        }
        long discount = discountType == DiscountType.FIXED
                ? discountValue
                : eligibleAmount * discountValue / 100;
        if (maxDiscount != null) {
            discount = Math.min(discount, maxDiscount);
        }
        return Math.min(discount, eligibleAmount);
    }
}
