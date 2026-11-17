package com.cartmate.cartmate.boundedContext.orderService.order.dto.personal;

import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.OrderReturnPreview;

public record OrderReturnPreviewResponse(
        long refundAmount
) {

    public static OrderReturnPreviewResponse from(OrderReturnPreview preview) {
        return new OrderReturnPreviewResponse(preview.refundAmount());
    }
}
