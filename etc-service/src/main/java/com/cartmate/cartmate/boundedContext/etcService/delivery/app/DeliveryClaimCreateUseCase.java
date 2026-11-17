package com.cartmate.cartmate.boundedContext.etcService.delivery.app;

import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.DeliveryClaimCreateCommand;
import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.DeliveryClaimResponse;
import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.DeliveryOrderRef;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimItemResult;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimStatus;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimType;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.Delivery;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryClaim;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryClaimItem;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryItem;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryStatus;
import com.cartmate.cartmate.boundedContext.etcService.delivery.exception.DeliveryErrorCode;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryClaimItemRepository;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryClaimRepository;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryItemRepository;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryRepository;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import com.cartmate.cartmate.shared.delivery.event.DeliveryClaimReturnRequestedEvent;
import com.cartmate.cartmate.shared.delivery.event.PendingReturn;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DeliveryClaimCreateUseCase {

    // 승인된 반품으로 보는 클레임 상태
    private static final List<ClaimStatus> RETURNED_STATUSES = List.of(ClaimStatus.APPROVED, ClaimStatus.COMPLETED);

    private final DeliveryRepository deliveryRepository;
    private final DeliveryItemRepository deliveryItemRepository;
    private final DeliveryClaimRepository deliveryClaimRepository;
    private final DeliveryClaimItemRepository deliveryClaimItemRepository;
    private final DomainEventPublisher eventPublisher;

    // 접수 락을 잡으려고 배송의 주문을 조회한다. 없는 배송이면 접수할 때와 같은 오류를 낸다.
    public DeliveryOrderRef orderRefOf(Long deliveryId) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new ServiceException(DeliveryErrorCode.DELIVERY_NOT_FOUND));
        return new DeliveryOrderRef(delivery.getOrderId(), delivery.getPurchaseType());
    }

    // 수령인(구매자) 본인이 배송완료된 배송에서 상품을 골라 교환·환불을 접수한다. 구매확정 이후에는 접수할 수 없다.
    public DeliveryClaimResponse create(DeliveryClaimCreateCommand command) {
        validate(command);

        // 구매확정과는 같은 배송 분산락(delivery:{deliveryId})으로 줄을 선다. 확정이 먼저면 아래 상태 검사에서 거부된다.
        // 락이 뚫려도 배송 버전을 강제로 올려 둘 중 늦게 커밋한 쪽이 실패한다.
        Delivery delivery = deliveryRepository.findByIdForClaim(command.deliveryId())
                .orElseThrow(() -> new ServiceException(DeliveryErrorCode.DELIVERY_NOT_FOUND));

        if (!delivery.getMemberId().equals(command.memberId())) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_ACCESS_DENIED);
        }
        if (delivery.getStatus() != DeliveryStatus.DELIVERED) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_INVALID_STATUS);
        }
        if (command.claimType() == ClaimType.REFUND && delivery.getPurchaseType() == PurchaseType.SHARED_GROUP) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_REFUND_NOT_ALLOWED);
        }

        Map<Long, DeliveryItem> deliveryItems = deliveryItemRepository.findByDeliveryId(command.deliveryId())
                .stream()
                .collect(Collectors.toMap(DeliveryItem::getId, Function.identity()));
        validateItems(command.items(), deliveryItems, approvedReturnedItemIds(command.deliveryId()));

        List<Long> requestedItemIds = command.items().stream()
                .map(DeliveryClaimCreateCommand.Item::deliveryItemId)
                .toList();
        if (deliveryClaimItemRepository.existsByDeliveryItemIdsAndClaimStatuses(requestedItemIds, ClaimStatus.inProgress())) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_ALREADY_EXISTS);
        }

        DeliveryClaim claim = DeliveryClaim.create(
                delivery, command.memberId(), command.claimType(), command.reason(), normalize(command.detail()));
        // 이번 신청을 저장하기 전에 같은 주문의 처리 중인 다른 반품을 읽는다. 검사가 필요한 반품만 쓴다.
        List<PendingReturn> pendingReturns = claim.needsVerification() ? pendingReturns(delivery) : List.of();
        deliveryClaimRepository.save(claim);

        List<DeliveryClaimItem> claimItems = command.items().stream()
                .map(item -> DeliveryClaimItem.create(claim, deliveryItems.get(item.deliveryItemId()),
                        deliveryItems.get(item.deliveryItemId()).getQuantity()))
                .toList();
        deliveryClaimItemRepository.saveAll(claimItems);

        // 구매자 사정 반품은 쿠폰 최소 금액이 깨질 수 있어 주문 서버가 확인한 뒤에 접수된다.
        if (claim.needsVerification()) {
            eventPublisher.publish(new DeliveryClaimReturnRequestedEvent(
                    claim.getId(), delivery.getOrderId(), delivery.getPurchaseType(), claim.getMemberId(),
                    claimItems.stream()
                            .map(ci -> ci.getDeliveryItem().getOrderItemId())
                            .toList(),
                    pendingReturns));
        }

        return DeliveryClaimResponse.from(claim, claimItems);
    }

    // 같은 주문에서 처리 중인 다른 반품을 클레임별로 묶어 판매자 탓 여부와 함께 돌려준다(클레임 번호 순서).
    private List<PendingReturn> pendingReturns(Delivery delivery) {
        Map<Long, List<DeliveryClaimItem>> byClaim = deliveryClaimItemRepository
                .findPendingReturnItems(delivery.getOrderId(), delivery.getPurchaseType(), ClaimType.REFUND,
                        ClaimStatus.inProgress(), ClaimItemResult.APPROVED)
                .stream()
                .collect(Collectors.groupingBy(ci -> ci.getClaim().getId(), LinkedHashMap::new, Collectors.toList()));
        return byClaim.values().stream()
                .map(items -> new PendingReturn(
                        items.get(0).getClaim().getReason().isSellerFault(),
                        items.stream().map(ci -> ci.getDeliveryItem().getOrderItemId()).toList()))
                .toList();
    }

    // 이미 승인된 반품이 있는 배송상품. 돌려보낸 상품이라 다시 반품·교환할 수 없다.
    private Set<Long> approvedReturnedItemIds(Long deliveryId) {
        return new HashSet<>(deliveryClaimItemRepository.findApprovedReturnedDeliveryItemIds(
                deliveryId, ClaimType.REFUND, RETURNED_STATUSES, ClaimItemResult.APPROVED));
    }

    private void validate(DeliveryClaimCreateCommand command) {
        if (command.deliveryId() == null) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_ID_REQUIRED);
        }
        if (command.memberId() == null) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_MEMBER_ID_REQUIRED);
        }
        if (command.claimType() == null) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_TYPE_REQUIRED);
        }
        if (command.reason() == null) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_REASON_REQUIRED);
        }
        if (command.detail() != null && command.detail().trim().length() > DeliveryClaim.DETAIL_MAX_LENGTH) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_DETAIL_TOO_LONG);
        }
        if (command.items() == null || command.items().isEmpty()) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_ITEMS_REQUIRED);
        }

        Set<Long> seen = new HashSet<>();
        for (DeliveryClaimCreateCommand.Item item : command.items()) {
            if (item == null || item.deliveryItemId() == null) {
                throw new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_ITEM_ID_REQUIRED);
            }
            if (!seen.add(item.deliveryItemId())) {
                throw new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_ITEM_DUPLICATED);
            }
        }
    }

    // 선택한 상품이 모두 이 배송의 취소되지 않은 상품이고, 이미 반품된 상품이 아닌지 확인한다.
    private void validateItems(List<DeliveryClaimCreateCommand.Item> items, Map<Long, DeliveryItem> deliveryItems,
                               Set<Long> approvedReturned) {
        for (DeliveryClaimCreateCommand.Item item : items) {
            DeliveryItem deliveryItem = deliveryItems.get(item.deliveryItemId());
            if (deliveryItem == null) {
                throw new ServiceException(DeliveryErrorCode.DELIVERY_ITEM_NOT_FOUND);
            }
            // 취소된 상품은 보내지 않았고 이미 환불됐다.
            if (deliveryItem.isCanceled()) {
                throw new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_ITEM_CANCELED);
            }
            if (approvedReturned.contains(deliveryItem.getId())) {
                throw new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_ITEM_ALREADY_RETURNED);
            }
        }
    }

    // 앞뒤 공백을 제거하고 비어 있으면 null로 저장한다.
    private String normalize(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        return detail.trim();
    }
}
