package com.cartmate.cartmate.boundedContext.orderService.order.dto.personal;

import java.util.List;

// 결제 대기 주문은 본문 없이 주문 전체를 취소하므로 결제 완료 주문에서만 항목 목록을 확인한다
public record OrderCancelRequest(
        List<Long> orderItemIds
) {
}
