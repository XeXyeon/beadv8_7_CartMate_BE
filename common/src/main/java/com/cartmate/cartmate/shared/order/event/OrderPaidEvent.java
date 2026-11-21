package com.cartmate.cartmate.shared.order.event;

import com.cartmate.cartmate.shared.order.OrderType;

import com.cartmate.cartmate.global.event.DomainEvent;
import com.cartmate.cartmate.shared.order.dto.DeliveryAddressInfo;

import java.time.LocalDateTime;
import java.util.List;

public record OrderPaidEvent(
        Long orderId,
        String orderNumber,
        Long memberId,
        OrderType orderType,
        Long individualGroupPurchaseId,
        LocalDateTime paidAt,
        DeliveryAddressInfo deliveryAddress,
        List<Item> items,
        boolean directOrder   // 바로구매 주문이면 true. 장바구니는 바로구매 주문을 정리하지 않는다
) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "Order";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(orderId);
    }

    // 할인 두 칸은 결제 때 이 상품 줄에 고정 배분된 쿠폰 할인이다(줄 전체 수량 기준). 쿠폰이 없거나 이 칸이 생기기 전 이벤트면 null이고 0으로 본다.
    // 쿠폰 하나는 부담 주체가 하나라 두 칸 중 하나만 값이 있다.
    public record Item(
            Long orderItemId,
            Long productId,
            Long inventoryId,
            Long sellerId,
            String productName,
            String optionName,
            int quantity,
            long unitPrice,
            Long sellerDiscountAmount,   // 판매자가 부담하는 할인. 판매자 정산에서 빠진다.
            Long platformDiscountAmount  // 플랫폼이 부담하는 할인. 정산 때 플랫폼 지갑이 판매자에게 대신 낸다.
    ) {
        public Item(Long orderItemId, Long productId, Long inventoryId, Long sellerId, String productName,
                    String optionName, int quantity, long unitPrice) {
            this(orderItemId, productId, inventoryId, sellerId, productName, optionName, quantity, unitPrice,
                    null, null);
        }
    }
}
