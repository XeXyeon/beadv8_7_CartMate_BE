package com.cartmate.cartmate.boundedContext.orderService.order.app.itemcancel;

import com.cartmate.cartmate.boundedContext.orderService.order.app.groupshared.SharedGroupOrderFacade;
import com.cartmate.cartmate.boundedContext.orderService.order.app.personal.OrderFacade;
import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 구매자 취소, 판매글 내림, 판매자 사유 취소가 함께 쓰는 마지막 단계: 고른 항목을 이 사유로 취소하고 판매자 몫 환불 이벤트를 낸다.
 * orderItemIds가 null이면 그 판매자의 취소되지 않은 항목 전부. 돌려주는 값은 새로 만든 취소 건 ID다.
 */
@Service
@RequiredArgsConstructor
public class ItemCancelService {

    private final OrderFacade orderFacade;
    private final SharedGroupOrderFacade sharedGroupOrderFacade;

    public Optional<Long> cancel(PurchaseType purchaseType, Long orderId, Long sellerId, Collection<Long> orderItemIds,
                                 DeliveryCancelReason reason) {
        if (purchaseType == PurchaseType.SHARED_GROUP) {
            return sharedGroupOrderFacade.cancelItems(orderId, sellerId, orderItemIds, reason);
        }
        return orderFacade.cancelItems(orderId, sellerId, orderItemIds, reason);
    }

    public List<Long> findUncanceledItemIds(PurchaseType purchaseType, Long orderId, Long sellerId) {
        if (purchaseType == PurchaseType.SHARED_GROUP) {
            return sharedGroupOrderFacade.findUncanceledItemIds(orderId, sellerId);
        }
        return orderFacade.findUncanceledItemIds(orderId, sellerId);
    }
}
