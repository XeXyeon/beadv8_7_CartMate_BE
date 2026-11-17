package com.cartmate.cartmate.boundedContext.orderService.order.domain.groupshared;

import java.util.List;

/**
 * 공동 주문에서 항목을 취소한 결과.
 *
 * @param canceled      이번에 새로 취소된 항목. 비어 있으면 아무것도 취소하지 않았다
 * @param amount        이번에 취소된 항목 금액 합
 * @param orderCanceled 이번 취소로 주문의 모든 항목이 취소됐는지
 */
public record SharedItemCancelOutcome(List<SharedGroupOrderItem> canceled, long amount, boolean orderCanceled) {

    static SharedItemCancelOutcome none() {
        return new SharedItemCancelOutcome(List.of(), 0, false);
    }

    public boolean isEmpty() {
        return canceled.isEmpty();
    }

    public void linkCancel(Long cancelId) {
        canceled.forEach(item -> item.linkCancel(cancelId));
    }
}
