package com.cartmate.cartmate.boundedContext.orderService.order.domain.personal;

import java.util.List;

/**
 * 개인 주문에서 항목을 취소한 결과.
 *
 * @param parts            판매자별로 이번에 새로 취소된 항목과 그 판매자 몫 환불 금액. 비어 있으면 아무것도 취소하지 않았다
 * @param refundAmount     이번 취소로 돌려줄 금액 합계
 * @param orderCanceled    이번 취소로 주문의 모든 항목이 취소됐는지
 * @param couponReturnable 이번 취소로 쿠폰을 돌려줘야 하는지
 */
public record ItemCancelOutcome(List<SellerPart> parts, long refundAmount,
                                boolean orderCanceled, boolean couponReturnable) {

    public record SellerPart(Long sellerId, List<OrderItem> canceled, long refundAmount) {

        public void linkCancel(Long cancelId) {
            canceled.forEach(item -> item.linkCancel(cancelId));
        }
    }

    static ItemCancelOutcome none() {
        return new ItemCancelOutcome(List.of(), 0, false, false);
    }

    public boolean isEmpty() {
        return parts.isEmpty();
    }

    public List<OrderItem> canceled() {
        return parts.stream().flatMap(part -> part.canceled().stream()).toList();
    }
}
