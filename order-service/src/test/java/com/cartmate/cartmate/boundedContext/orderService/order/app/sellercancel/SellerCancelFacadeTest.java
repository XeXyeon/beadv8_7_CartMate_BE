package com.cartmate.cartmate.boundedContext.orderService.order.app.sellercancel;

import com.cartmate.cartmate.boundedContext.orderService.order.app.itemcancel.ItemCancelService;
import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import com.cartmate.cartmate.shared.delivery.event.DeliverySellerCanceledEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SellerCancelFacadeTest {

    private static final long ORDER_ID = 100L;
    private static final long SELLER_ID = 7L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 11, 12, 0);

    private final ItemCancelService itemCancelService = mock(ItemCancelService.class);
    private final SellerCancelFacade facade = new SellerCancelFacade(itemCancelService);

    @Test
    @DisplayName("판매자 사유 취소를 받으면 배송 서버가 취소한 그 항목만 판매자가 고른 사유로, 할인은 비율대로 나눠 취소한다")
    void apply_sellerCanceledWithItemIds_cancelsOnlyThoseItems() {
        facade.apply(new DeliverySellerCanceledEvent(PurchaseType.INDIVIDUAL_GROUP, ORDER_ID, SELLER_ID,
                DeliveryCancelReason.SELLER_OUT_OF_STOCK, NOW, List.of(11L, 12L)));

        verify(itemCancelService).cancel(PurchaseType.INDIVIDUAL_GROUP, ORDER_ID, SELLER_ID, List.of(11L, 12L),
                DeliveryCancelReason.SELLER_OUT_OF_STOCK);
    }

    @Test
    @DisplayName("공동구매의 판매자 사유 취소도 배송 서버가 취소한 그 항목만 취소한다")
    void apply_sharedGroupWithItemIds_cancelsOnlyThoseItems() {
        facade.apply(new DeliverySellerCanceledEvent(PurchaseType.SHARED_GROUP, ORDER_ID, SELLER_ID,
                DeliveryCancelReason.SELLER_PRODUCT_ISSUE, NOW, List.of(21L)));

        verify(itemCancelService).cancel(PurchaseType.SHARED_GROUP, ORDER_ID, SELLER_ID, List.of(21L),
                DeliveryCancelReason.SELLER_PRODUCT_ISSUE);
    }
}
