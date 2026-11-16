package com.cartmate.cartmate.boundedContext.orderService.order.dto.personal;

import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.OrderCancelPreview;

public record OrderCancelPreviewResponse(
        long refundAmount,
        boolean couponReturned
) {

    public static OrderCancelPreviewResponse from(OrderCancelPreview preview) {
        return new OrderCancelPreviewResponse(preview.refundAmount(), preview.couponReturned());
    }
}
