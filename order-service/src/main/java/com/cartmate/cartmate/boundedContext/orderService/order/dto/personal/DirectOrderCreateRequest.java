package com.cartmate.cartmate.boundedContext.orderService.order.dto.personal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// 바로구매: 장바구니를 거치지 않고 재고 하나를 주문한다
public record DirectOrderCreateRequest(
        @NotNull
        Long inventoryId,
        @Min(1)
        @Max(999)
        int quantity,
        @NotNull
        @Valid
        DeliveryAddressRequest deliveryAddress
) {
}
