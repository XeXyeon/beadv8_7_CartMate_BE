package com.cartmate.cartmate.boundedContext.orderService.order.domain.personal;

import java.util.List;

/**
 * 개인 주문에서 항목을 취소한 결과.
 *
 * @param canceled         이번에 새로 취소된 항목. 비어 있으면 아무것도 취소하지 않았다
 * @param refundAmount     이번 취소로 돌려줄 금액
 * @param orderCanceled    이번 취소로 주문의 모든 항목이 취소됐는지
 * @param couponReturnable 이번 취소로 쿠폰을 돌려줘야 하는지
 */
public record ItemCancelOutcome(List<OrderItem> canceled, long refundAmount,
                                boolean orderCanceled, boolean couponReturnable) {

    static ItemCancelOutcome none() {
        return new ItemCancelOutcome(List.of(), 0, false, false);
    }

    public boolean isEmpty() {
        return canceled.isEmpty();
    }

    public void linkCancel(Long cancelId) {
        canceled.forEach(item -> item.linkCancel(cancelId));
    }
}
