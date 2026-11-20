package com.cartmate.cartmate.shared.order.event;

import com.cartmate.cartmate.global.event.DomainEvent;

// 주문에 쓴 쿠폰을 돌려 달라는 요청. 결제 실패·결제 대기 주문 취소·쿠폰 혜택이 0이 된 일부 취소·반품 때 주문 서버가 낸다.
// 상품 서버(쿠폰)가 받아 그 주문에 쓴 쿠폰만 보유로 돌린다. 이미 돌렸거나 다른 주문에 쓴 쿠폰이면 아무것도 하지 않는다(여러 번 와도 같다).
public record OrderCouponRestoreRequestedEvent(
        Long orderId,
        Long memberId,
        Long memberCouponId,
        String orderType // 쿠폰 쪽 주문 구분(개인 주문은 "ORDER")
) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "Order";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(orderId);
    }
}
