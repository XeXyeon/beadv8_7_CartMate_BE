package com.cartmate.cartmate.boundedContext.orderService.order.app.itemcancel;

import com.cartmate.cartmate.boundedContext.orderService.order.app.groupshared.SharedGroupOrderFacade;
import com.cartmate.cartmate.boundedContext.orderService.order.app.personal.OrderFacade;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.itemcancel.CouponMode;
import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ItemCancelServiceTest {

    private static final long ORDER_ID = 100L;
    private static final long SELLER_ID = 7L;

    private final OrderFacade orderFacade = mock(OrderFacade.class);
    private final SharedGroupOrderFacade sharedGroupOrderFacade = mock(SharedGroupOrderFacade.class);
    private final ItemCancelService service = new ItemCancelService(orderFacade, sharedGroupOrderFacade);

    @Test
    @DisplayName("개인 주문이면 개인 주문의 항목 취소를 쿠폰 계산 방식과 함께 부르고 취소 건 ID를 돌려준다")
    void cancelsPersonalOrderItems() {
        when(orderFacade.cancelItems(ORDER_ID, SELLER_ID, List.of(1L), DeliveryCancelReason.BUYER_CANCELED,
                CouponMode.RECALCULATE)).thenReturn(Optional.of(9L));

        Optional<Long> cancelId = service.cancel(PurchaseType.INDIVIDUAL_GROUP, ORDER_ID, SELLER_ID, List.of(1L),
                DeliveryCancelReason.BUYER_CANCELED, CouponMode.RECALCULATE);

        assertThat(cancelId).contains(9L);
        verifyNoInteractions(sharedGroupOrderFacade);
    }

    @Test
    @DisplayName("공동구매면 공동 주문의 항목 취소를 부른다")
    void cancelsSharedGroupOrderItems() {
        when(sharedGroupOrderFacade.cancelItems(ORDER_ID, SELLER_ID, null, DeliveryCancelReason.SALES_STOPPED))
                .thenReturn(Optional.of(3L));

        Optional<Long> cancelId = service.cancel(PurchaseType.SHARED_GROUP, ORDER_ID, SELLER_ID, null,
                DeliveryCancelReason.SALES_STOPPED, CouponMode.PROPORTIONAL);

        assertThat(cancelId).contains(3L);
        verifyNoInteractions(orderFacade);
    }
}
