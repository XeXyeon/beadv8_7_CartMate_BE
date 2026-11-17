package com.cartmate.cartmate.boundedContext.orderService.order.app.sellercancel;

import com.cartmate.cartmate.boundedContext.orderService.order.app.itemcancel.ItemCancelService;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.itemcancel.CouponMode;
import com.cartmate.cartmate.shared.delivery.event.DeliverySellerCanceledEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 사유 취소: 배송 서버가 상품을 이미 취소했으므로, 거절하지 않고 배송 서버가 취소한 그 항목을 취소하고 환불 이벤트를 낸다.
 * 결제 완료가 아니거나 취소할 항목이 없으면(같은 이벤트 재수신, 판매 중지와 겹침) 아무것도 하지 않는다.
 * 판매자 사정이라 구매자 할인을 빼앗지 않도록 할인은 비율대로 나눈다.
 */
@Service
@RequiredArgsConstructor
public class SellerCancelFacade {

    private final ItemCancelService itemCancelService;

    @Transactional
    public void apply(DeliverySellerCanceledEvent event) {
        itemCancelService.cancel(event.purchaseType(), event.orderId(), event.sellerId(), event.orderItemIds(),
                event.cancelReason(), CouponMode.PROPORTIONAL);
    }
}
