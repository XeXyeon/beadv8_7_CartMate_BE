package com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto;

import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimItemResult;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryClaim;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

// 교환·환불 처리 요청. 판매자가 클레임의 모든 상품에 승인이나 거절을 한 번에 정해 보낸다.
public record DeliveryClaimDecideRequest(
        @NotEmpty
        List<@NotNull @Valid Item> items
) {
    @Schema(name = "DeliveryClaimDecideItemRequest")
    public record Item(
            @NotNull
            Long deliveryItemId,

            @NotNull
            ClaimItemResult result,

            // 거절하는 상품은 사유가 필수다
            @Size(max = DeliveryClaim.DETAIL_MAX_LENGTH)
            String rejectReason
    ) {}
}
