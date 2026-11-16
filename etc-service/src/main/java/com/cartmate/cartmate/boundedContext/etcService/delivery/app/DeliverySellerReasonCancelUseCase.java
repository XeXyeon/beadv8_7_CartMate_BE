package com.cartmate.cartmate.boundedContext.etcService.delivery.app;

import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.DeliveryResponse;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.Delivery;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryItem;
import com.cartmate.cartmate.boundedContext.etcService.delivery.exception.DeliveryErrorCode;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryRepository;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import com.cartmate.cartmate.shared.delivery.event.DeliverySellerCanceledEvent;
import com.cartmate.cartmate.shared.member.SellerQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DeliverySellerReasonCancelUseCase {

    private final DeliveryRepository deliveryRepository;
    private final SellerQuery sellerQuery;
    private final DomainEventPublisher eventPublisher;

    // 판매자가 자기 배송의 상품을 골라 사유와 함께 취소하고, 새로 취소한 상품이 있을 때만 같은 트랜잭션에서 주문 서버에 알린다.
    // 주문 서버는 이 이벤트의 주문 항목을 취소하고 환불한다.
    public DeliveryResponse cancel(Long deliveryId, Long memberId, DeliveryCancelReason reason, List<Long> deliveryItemIds) {
        if (memberId == null) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_MEMBER_ID_REQUIRED);
        }
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new ServiceException(DeliveryErrorCode.DELIVERY_NOT_FOUND));

        if (!sellerQuery.isSellerOwner(memberId, delivery.getSellerId())) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_ACCESS_DENIED);
        }

        LocalDateTime canceledAt = LocalDateTime.now();
        Set<Long> ids = deliveryItemIds == null ? Set.of() : new HashSet<>(deliveryItemIds);
        List<DeliveryItem> canceled = delivery.cancelItemsBySeller(ids, reason, canceledAt);
        if (!canceled.isEmpty()) {
            eventPublisher.publish(new DeliverySellerCanceledEvent(
                    delivery.getPurchaseType(), delivery.getOrderId(), delivery.getSellerId(), reason, canceledAt,
                    canceled.stream().map(DeliveryItem::getOrderItemId).toList()));
        }
        return DeliveryResponse.from(delivery);
    }
}
