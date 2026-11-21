package com.cartmate.cartmate.boundedContext.orderService.order.dto.groupshared;

import com.cartmate.cartmate.shared.order.dto.TeamDeliveryAddress;
import jakarta.validation.Valid;

public record SharedGroupPaymentRequest(
        @Valid
        TeamDeliveryAddress teamDeliveryAddress,
        // 분담금에 쓸 내 쿠폰(플랫폼 쿠폰만). 없으면 null
        Long memberCouponId
) {
}
