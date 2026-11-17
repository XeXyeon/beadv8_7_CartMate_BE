package com.cartmate.cartmate.boundedContext.etcService.delivery.app;

import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.DeliveryClaimDecideRequest;
import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.SellerClaimResponse;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimItemResult;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimStatus;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimType;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.Delivery;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryClaim;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryClaimItem;
import com.cartmate.cartmate.boundedContext.etcService.delivery.exception.DeliveryErrorCode;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryClaimItemRepository;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryClaimRepository;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.shared.delivery.event.DeliveryClaimReturnApprovedEvent;
import com.cartmate.cartmate.shared.member.SellerQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DeliveryClaimDecideUseCase {

    private final DeliveryClaimRepository deliveryClaimRepository;
    private final DeliveryClaimItemRepository deliveryClaimItemRepository;
    private final SellerQuery sellerQuery;
    private final DomainEventPublisher eventPublisher;

    // 해당 배송의 판매자가 접수된 교환·환불의 모든 상품에 승인이나 거절을 한 번에 정한다.
    // 하나라도 승인하면 클레임이 승인되고, 모두 거절하면 거절된다. 반품은 승인된 상품만 담아 환불을 요청한다.
    // 이미 처리된 클레임에 같은 결정을 다시 보내면 이벤트를 다시 내지 않고 현재 결과를 돌려준다.
    public SellerClaimResponse decide(Long claimId, Long memberId, List<DeliveryClaimDecideRequest.Item> decisions) {
        if (memberId == null) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_MEMBER_ID_REQUIRED);
        }
        DeliveryClaim claim = deliveryClaimRepository.findById(claimId)
                .orElseThrow(() -> new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_NOT_FOUND));
        Delivery delivery = claim.getDelivery();

        if (!sellerQuery.isSellerOwner(memberId, delivery.getSellerId())) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_ACCESS_DENIED);
        }

        List<DeliveryClaimItem> claimItems =
                deliveryClaimItemRepository.findAllWithDeliveryItemByClaimIds(List.of(claim.getId()));
        Map<Long, DeliveryClaimDecideRequest.Item> byItem = validate(decisions, claimItems);

        if (claim.getStatus() == ClaimStatus.APPROVED || claim.getStatus() == ClaimStatus.REJECTED) {
            if (sameDecision(claimItems, byItem)) {
                return SellerClaimResponse.from(claim, claimItems);
            }
            throw new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_INVALID_STATUS);
        }

        claimItems.forEach(claimItem -> {
            DeliveryClaimDecideRequest.Item decision = byItem.get(claimItem.getDeliveryItem().getId());
            claimItem.decide(decision.result(), decision.rejectReason());
        });
        List<Long> approvedOrderItemIds = claimItems.stream()
                .filter(claimItem -> claimItem.getResult() == ClaimItemResult.APPROVED)
                .map(claimItem -> claimItem.getDeliveryItem().getOrderItemId())
                .toList();
        claim.decide(!approvedOrderItemIds.isEmpty());

        if (claim.getClaimType() == ClaimType.REFUND && !approvedOrderItemIds.isEmpty()) {
            eventPublisher.publish(new DeliveryClaimReturnApprovedEvent(
                    claim.getId(),
                    delivery.getId(),
                    delivery.getOrderId(),
                    delivery.getPurchaseType(),
                    claim.getMemberId(),
                    delivery.getSellerId(),
                    claim.getReason().isSellerFault(),
                    approvedOrderItemIds
            ));
        }

        return SellerClaimResponse.from(claim, claimItems);
    }

    // 결정이 클레임의 상품과 정확히 일치하는지(빠짐·다른 상품·중복 없음), 거절 사유가 있는지 확인한다.
    private Map<Long, DeliveryClaimDecideRequest.Item> validate(
            List<DeliveryClaimDecideRequest.Item> decisions, List<DeliveryClaimItem> claimItems
    ) {
        if (decisions == null || decisions.isEmpty()) {
            throw mismatch();
        }
        Map<Long, DeliveryClaimDecideRequest.Item> byItem = new HashMap<>();
        for (DeliveryClaimDecideRequest.Item decision : decisions) {
            validateDecision(decision);
            // 같은 상품을 두 번 정할 수 없다
            if (byItem.containsKey(decision.deliveryItemId())) {
                throw mismatch();
            }
            byItem.put(decision.deliveryItemId(), decision);
        }
        Set<Long> claimItemIds = claimItems.stream()
                .map(claimItem -> claimItem.getDeliveryItem().getId())
                .collect(Collectors.toSet());
        if (!claimItemIds.equals(byItem.keySet())) {
            throw mismatch();
        }
        return byItem;
    }

    // 결정 한 줄의 모양: 상품과 결과가 있어야 하고, 거절이면 사유가 있어야 한다.
    private void validateDecision(DeliveryClaimDecideRequest.Item decision) {
        if (decision == null || decision.deliveryItemId() == null || decision.result() == null) {
            throw mismatch();
        }
        if (decision.result() == ClaimItemResult.REJECTED) {
            validateRejectReason(decision.rejectReason());
        }
    }

    private static ServiceException mismatch() {
        return new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_DECISION_ITEMS_MISMATCH);
    }

    private void validateRejectReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_REJECT_REASON_REQUIRED);
        }
        if (reason.length() > DeliveryClaim.DETAIL_MAX_LENGTH) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_REJECT_REASON_TOO_LONG);
        }
    }

    private boolean sameDecision(List<DeliveryClaimItem> claimItems, Map<Long, DeliveryClaimDecideRequest.Item> byItem) {
        return claimItems.stream().allMatch(claimItem ->
                claimItem.getResult() == byItem.get(claimItem.getDeliveryItem().getId()).result());
    }
}
