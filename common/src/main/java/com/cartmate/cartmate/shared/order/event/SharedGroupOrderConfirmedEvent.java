package com.cartmate.cartmate.shared.order.event;

import com.cartmate.cartmate.global.event.DomainEvent;
import com.cartmate.cartmate.shared.order.dto.TeamDeliveryAddress;

import java.time.LocalDateTime;
import java.util.List;

public record SharedGroupOrderConfirmedEvent(
        Long sharedGroupOrderId,
        Long sharedGroupPurchaseId,
        Long creatorMemberId,
        LocalDateTime confirmedAt,
        TeamDeliveryAddress teamDeliveryAddress,
        List<Item> items
) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "SharedGroupOrder";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(sharedGroupOrderId);
    }

    public record Item(
            Long orderItemId,
            Long productId,
            Long inventoryId,
            Long sellerId,
            String productName,
            String optionName,
            int quantity,
            long unitPrice,
            // 참여자들이 분담금에 쓴 플랫폼 쿠폰 할인 중 이 줄의 몫(줄 전체 수량 기준). 정산 때 플랫폼 지갑이 판매자에게 대신 낸다.
            // 쿠폰을 아무도 안 썼거나 이 칸이 생기기 전 이벤트면 null이고 0으로 본다
            Long platformDiscountAmount
    ) {
        public Item(Long orderItemId, Long productId, Long inventoryId, Long sellerId, String productName,
                    String optionName, int quantity, long unitPrice) {
            this(orderItemId, productId, inventoryId, sellerId, productName, optionName, quantity, unitPrice, null);
        }
    }
}
