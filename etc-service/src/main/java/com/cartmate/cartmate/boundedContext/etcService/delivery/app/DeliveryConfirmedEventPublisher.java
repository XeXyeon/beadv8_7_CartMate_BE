package com.cartmate.cartmate.boundedContext.etcService.delivery.app;

import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimItemResult;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimStatus;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimType;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.Delivery;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryItem;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryClaimItemRepository;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryItemRepository;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.shared.delivery.event.DeliveryConfirmedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class DeliveryConfirmedEventPublisher {

    private final DeliveryItemRepository deliveryItemRepository;
    private final DeliveryClaimItemRepository deliveryClaimItemRepository;
    private final DomainEventPublisher eventPublisher;

    // 구매확정된 배송과 그 상품 정보를 담아 DeliveryConfirmedEvent를 발행한다.
    // 배송 전에 취소된 상품은 팔리지 않았으므로 뺀다. 구매확정 전에 환불이 완료된 수량도 상품 수량에서 빼고, 전량 환불된 상품은 제외한다.
    // 남는 상품이 하나도 없으면 정산할 것이 없으므로 이벤트를 발행하지 않는다.
    public void publish(Delivery delivery) {
        Map<Long, Long> refundedQuantities = refundedQuantities(delivery.getId());

        List<DeliveryConfirmedEvent.Item> items = deliveryItemRepository.findByDeliveryId(delivery.getId()).stream()
                .filter(item -> !item.isCanceled())
                .map(item -> toItem(item, refundedQuantities.getOrDefault(item.getId(), 0L)))
                .filter(item -> item.quantity() > 0)
                .toList();

        if (items.isEmpty()) {
            return;
        }

        eventPublisher.publish(new DeliveryConfirmedEvent(
                delivery.getId(), delivery.getSellerId(), delivery.getOrderId(), delivery.getPurchaseType(),
                delivery.getCreatedAt(), delivery.getConfirmedAt(), items
        ));
    }

    // 완료된 환불 클레임에서 승인된 상품의 수량을 배송상품별로 모은다(거절된 상품은 정산에서 빼지 않는다). 교환은 판매가 유지되므로 포함하지 않는다.
    private Map<Long, Long> refundedQuantities(Long deliveryId) {
        return deliveryClaimItemRepository
                .sumQuantityByDeliveryItem(deliveryId, ClaimType.REFUND, ClaimStatus.COMPLETED, ClaimItemResult.APPROVED).stream()
                .collect(Collectors.toMap(
                        DeliveryClaimItemRepository.ClaimedQuantity::getDeliveryItemId,
                        DeliveryClaimItemRepository.ClaimedQuantity::getQuantity));
    }

    // 배송 수량에서 환불된 수량을 뺀 값으로 이벤트 상품을 만든다. 쿠폰 할인도 남은 수량만큼으로 줄여 보낸다.
    private DeliveryConfirmedEvent.Item toItem(DeliveryItem item, long refundedQuantity) {
        long quantity = item.getQuantity() - refundedQuantity;
        return new DeliveryConfirmedEvent.Item(
                item.getOrderItemId(), item.getProductName(), item.getUnitPrice(), quantity,
                item.sellerDiscountFor(quantity), item.platformDiscountFor(quantity));
    }
}
