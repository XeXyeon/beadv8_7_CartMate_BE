package com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto;

import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimItemResult;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimReason;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimStatus;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.RefundStatus;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimType;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryClaim;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryClaimItem;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

public record DeliveryClaimResponse(
        Long claimId,
        Long deliveryId,
        ClaimType claimType,
        ClaimReason reason,
        String detail,
        String notAcceptedCode,          // 접수 검사에서 거절된 이유 코드. 아니면 null
        ClaimStatus status,
        RefundStatus refundStatus,
        LocalDateTime createdAt,
        boolean partiallyApproved,       // 승인된 상품과 거절된 상품이 섞였는지. 화면에 "일부 승인"으로 보여 준다
        List<Item> items
) {
    public static DeliveryClaimResponse from(DeliveryClaim claim, List<DeliveryClaimItem> claimItems) {
        return new DeliveryClaimResponse(
                claim.getId(),
                claim.getDelivery().getId(),
                claim.getClaimType(),
                claim.getReason(),
                claim.getDetail(),
                claim.getNotAcceptedCode(),
                claim.getStatus(),
                claim.getRefundStatus(),
                claim.getCreatedAt(),
                isPartiallyApproved(claimItems),
                claimItems.stream().map(Item::from).toList()
        );
    }

    static boolean isPartiallyApproved(List<DeliveryClaimItem> claimItems) {
        return claimItems.stream().anyMatch(item -> item.getResult() == ClaimItemResult.APPROVED)
                && claimItems.stream().anyMatch(item -> item.getResult() == ClaimItemResult.REJECTED);
    }

    @Schema(name = "DeliveryClaimItemResponse")
    public record Item(
            Long deliveryItemId,
            Long orderItemId,
            String productName,
            Long quantity,
            ClaimItemResult result,      // 판매자 결정. 결정 전이면 null
            String rejectReason          // 거절한 상품의 사유
    ) {
        public static Item from(DeliveryClaimItem claimItem) {
            return new Item(
                    claimItem.getDeliveryItem().getId(),
                    claimItem.getDeliveryItem().getOrderItemId(),
                    claimItem.getDeliveryItem().getProductName(),
                    claimItem.getQuantity(),
                    claimItem.getResult(),
                    claimItem.getRejectReason()
            );
        }
    }
}
