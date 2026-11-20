package com.cartmate.cartmate.boundedContext.orderService.order.out;

import com.cartmate.cartmate.shared.coupon.dto.CouponOption;
import com.cartmate.cartmate.shared.coupon.dto.CouponOrderTarget;
import com.cartmate.cartmate.shared.coupon.dto.CouponQuote;

import java.util.List;

public interface OrderCouponClient {

    String ORDER_TYPE = "ORDER";

    List<CouponOption> options(Long memberId, Long orderId, CouponOrderTarget target);

    CouponQuote quote(Long memberId, Long memberCouponId, Long orderId, CouponOrderTarget target);

    CouponQuote use(Long memberId, Long memberCouponId, Long orderId, CouponOrderTarget target);

    // 복구는 HTTP가 아니라 OrderCouponRestoreRequestedEvent로 요청한다(주문과 함께 커밋, 실패하면 다시 받는다)
}
