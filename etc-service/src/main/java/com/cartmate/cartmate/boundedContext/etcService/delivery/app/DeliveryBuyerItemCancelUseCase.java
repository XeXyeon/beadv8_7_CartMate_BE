package com.cartmate.cartmate.boundedContext.etcService.delivery.app;

import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.BuyerCancelVerdict;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.Delivery;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryOrderState;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryRepository;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import com.cartmate.cartmate.shared.delivery.event.DeliveryBuyerCancelResultEvent;
import com.cartmate.cartmate.shared.delivery.event.DeliveryBuyerCancelResultEvent.SellerResult;
import com.cartmate.cartmate.shared.order.event.OrderBuyerCancelRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DeliveryBuyerItemCancelUseCase {

    private static final PurchaseType PURCHASE_TYPE = PurchaseType.INDIVIDUAL_GROUP;

    private final DeliveryRepository deliveryRepository;
    private final DeliveryOrderStates deliveryOrderStates;
    private final DomainEventPublisher eventPublisher;

    // 구매자가 고른 상품을 판매자(배송)마다 요청 시각 기준으로 판정해 취소하고, 판매자별 결과를 이벤트 하나로 돌려준다.
    // 한 배송에서 실패하면 요청 전체가 롤백되고 Inbox가 다시 처리한다. 판정이 요청 시각 기준이라 다시 처리해도 결과가 같다.
    public void handle(OrderBuyerCancelRequestedEvent event) {
        Map<Long, Set<Long>> orderItemIdsBySeller = event.items().stream()
                .collect(Collectors.groupingBy(OrderBuyerCancelRequestedEvent.Item::sellerId, LinkedHashMap::new,
                        Collectors.mapping(OrderBuyerCancelRequestedEvent.Item::orderItemId, Collectors.toSet())));

        DeliveryOrderState state = deliveryOrderStates.lockForSellerCancel(event.orderId(), PURCHASE_TYPE).state();
        if (state.isCanceled()) {
            publish(event, orderItemIdsBySeller.keySet().stream()
                    .map(sellerId -> new SellerResult(sellerId, true, null))
                    .toList());
            return;
        }

        // 조회가 배송 ID 순이라 잠금도 그 순서로 잡힌다. 여러 배송을 함께 잠그는 경로끼리 교착하지 않게 하려는 것이다.
        Map<Long, Delivery> deliveryBySeller = deliveryRepository
                .findAllByOrderIdAndPurchaseTypeForUpdate(event.orderId(), PURCHASE_TYPE).stream()
                .collect(Collectors.toMap(Delivery::getSellerId, Function.identity()));
        LocalDateTime canceledAt = LocalDateTime.now();
        List<SellerResult> results = new ArrayList<>();
        orderItemIdsBySeller.forEach((sellerId, orderItemIds) -> results.add(judge(
                event, sellerId, orderItemIds, deliveryBySeller.get(sellerId), canceledAt)));
        publish(event, results);
    }

    private SellerResult judge(OrderBuyerCancelRequestedEvent event, Long sellerId, Set<Long> orderItemIds,
                               Delivery delivery, LocalDateTime canceledAt) {
        if (delivery == null) {
            deliveryOrderStates.markItemsCanceled(event.orderId(), PURCHASE_TYPE, orderItemIds);
            return new SellerResult(sellerId, true, null);
        }
        BuyerCancelVerdict verdict = delivery.buyerCancelVerdict(event.requestedAt());
        if (verdict != BuyerCancelVerdict.ALLOWED) {
            return new SellerResult(sellerId, false, verdict.getRejectCode().resultCode());
        }
        // 다른 취소가 먼저 처리해 이미 취소된 상품은 그대로 두고 취소됨으로 답한다.
        delivery.cancelItems(orderItemIds, DeliveryCancelReason.BUYER_CANCELED, canceledAt);
        return new SellerResult(sellerId, true, null);
    }

    private void publish(OrderBuyerCancelRequestedEvent event, List<SellerResult> results) {
        eventPublisher.publish(new DeliveryBuyerCancelResultEvent(event.requestId(), event.orderId(), results));
    }
}
