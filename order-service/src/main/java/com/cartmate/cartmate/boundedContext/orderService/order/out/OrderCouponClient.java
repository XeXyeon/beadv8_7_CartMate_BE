package com.cartmate.cartmate.boundedContext.orderService.order.out;

import com.cartmate.cartmate.shared.coupon.dto.CouponOption;
import com.cartmate.cartmate.shared.coupon.dto.CouponOrderTarget;
import com.cartmate.cartmate.shared.coupon.dto.CouponQuote;

import java.util.List;

public interface OrderCouponClient {

    // 쿠폰 쪽 주문 구분. 같은 주문 ID라도 구분이 다르면 다른 주문이다
    String ORDER_TYPE = "ORDER";
    String SHARED_GROUP_ORDER_TYPE = "SHARED_GROUP_ORDER";

    List<CouponOption> options(String orderType, Long memberId, Long orderId, CouponOrderTarget target);

    CouponQuote quote(String orderType, Long memberId, Long memberCouponId, Long orderId, CouponOrderTarget target);

    CouponQuote use(String orderType, Long memberId, Long memberCouponId, Long orderId, CouponOrderTarget target);

    default List<CouponOption> options(Long memberId, Long orderId, CouponOrderTarget target) {
        return options(ORDER_TYPE, memberId, orderId, target);
    }

    default CouponQuote quote(Long memberId, Long memberCouponId, Long orderId, CouponOrderTarget target) {
        return quote(ORDER_TYPE, memberId, memberCouponId, orderId, target);
    }

    default CouponQuote use(Long memberId, Long memberCouponId, Long orderId, CouponOrderTarget target) {
        return use(ORDER_TYPE, memberId, memberCouponId, orderId, target);
    }

    // 복구는 HTTP가 아니라 OrderCouponRestoreRequestedEvent로 요청한다(주문과 함께 커밋, 실패하면 다시 받는다)
}
