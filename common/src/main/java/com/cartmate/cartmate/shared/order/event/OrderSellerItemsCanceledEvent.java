package com.cartmate.cartmate.shared.order.event;

import com.cartmate.cartmate.global.event.DomainEvent;
import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;

import java.time.LocalDateTime;

/**
 * 결제 완료된 개인 주문에서 한 판매자의 상품만 취소됐을 때 order가 발행한다. (구매자 상품 취소, 판매글 내림으로 인한 취소, 판매자 사유 취소)
 *
 * 구매자 주문 취소용 {@link OrderCanceledEvent}와 구분하기 위해 따로 둔다 (그 이벤트는 전액 환불을 일으킴).
 *
 * <p>환불 중복 방지 키는 payment가 취소 건으로 정한다 ({@code RefundKey.orderItemCancel}).
 * 그래서 같은 판매자 몫이 여러 번 나눠 취소되면 건마다 환불되고, 같은 건이 다시 와도 한 번만 환불된다.
 *
 * @param cancelReason 그 판매자 몫이 취소된 사유
 * @param cancelId     주문 서버 취소 건 ID. 같은 판매자 몫이 여러 번 나눠 취소돼도 건마다 따로 환불하도록 싣는다.
 */
public record OrderSellerItemsCanceledEvent(
        Long orderId,
        Long buyerMemberId,
        Long sellerId,
        long amount,
        LocalDateTime canceledAt,
        DeliveryCancelReason cancelReason,
        Long cancelId
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
