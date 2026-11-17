package com.cartmate.cartmate.boundedContext.etcService.delivery.app;

import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.Delivery;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryItem;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryOrderState;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryStatus;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryRepository;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import com.cartmate.cartmate.shared.delivery.event.DeliveryBuyerCancelResultEvent;
import com.cartmate.cartmate.shared.delivery.event.DeliveryBuyerCancelResultEvent.SellerResult;
import com.cartmate.cartmate.shared.order.event.OrderBuyerCancelRequestedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryBuyerItemCancelUseCaseTest {

    private static final long ORDER_ID = 100L;
    private static final long REQUEST_ID = 5L;
    private static final long SELLER_A = 1007L;
    private static final long SELLER_B = 1009L;
    private static final LocalDateTime BEFORE_PREPARE = LocalDateTime.now().minusMinutes(1);
    private static final LocalDateTime AFTER_PREPARE = LocalDateTime.now().plusMinutes(1);

    private final DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
    private final DeliveryOrderStates deliveryOrderStates = mock(DeliveryOrderStates.class);
    private final DomainEventPublisher eventPublisher = mock(DomainEventPublisher.class);
    private final DeliveryOrderState state = mock(DeliveryOrderState.class);
    private final DeliveryBuyerItemCancelUseCase useCase =
            new DeliveryBuyerItemCancelUseCase(deliveryRepository, deliveryOrderStates, eventPublisher);

    private void given(boolean canceled, List<Delivery> deliveries) {
        when(state.isCanceled()).thenReturn(canceled);
        when(deliveryOrderStates.lockForSellerCancel(ORDER_ID, PurchaseType.INDIVIDUAL_GROUP))
                .thenReturn(new DeliveryOrderStates.Locked(state, false));
        when(deliveryRepository.findAllByOrderIdAndPurchaseTypeForUpdate(ORDER_ID, PurchaseType.INDIVIDUAL_GROUP))
                .thenReturn(deliveries);
    }

    private Delivery delivery(long sellerId) {
        return Delivery.create(7L, sellerId, ORDER_ID, "O-100", PurchaseType.INDIVIDUAL_GROUP,
                "수령인", "010", "00000", "서울", "101호", "key-" + sellerId, "hash");
    }

    private OrderBuyerCancelRequestedEvent request(LocalDateTime requestedAt, OrderBuyerCancelRequestedEvent.Item... items) {
        return new OrderBuyerCancelRequestedEvent(REQUEST_ID, ORDER_ID, 7L, requestedAt, List.of(items));
    }

    private OrderBuyerCancelRequestedEvent.Item item(long orderItemId, long sellerId) {
        return new OrderBuyerCancelRequestedEvent.Item(orderItemId, sellerId);
    }

    private void verifyResult(SellerResult... results) {
        verify(eventPublisher).publish(new DeliveryBuyerCancelResultEvent(REQUEST_ID, ORDER_ID, List.of(results)));
    }

    @Test
    @DisplayName("판매자마다 따로 판정해 취소할 수 있는 판매자 상품만 취소하고 나머지는 거절한다")
    void handle_mixedSellers_cancelsAllowedRejectsOthers() {
        Delivery a = delivery(SELLER_A);
        DeliveryItem apple = DeliveryItem.create(a, 11L, "사과", 1000L, 1L);
        DeliveryItem pear = DeliveryItem.create(a, 12L, "배", 1000L, 1L);
        Delivery b = delivery(SELLER_B);
        DeliveryItem milk = DeliveryItem.create(b, 21L, "우유", 1000L, 1L);
        b.prepare("CJ", "123");
        given(false, List.of(a, b));

        useCase.handle(request(AFTER_PREPARE, item(11L, SELLER_A), item(21L, SELLER_B)));

        assertThat(apple.getCancelReason()).isEqualTo(DeliveryCancelReason.BUYER_CANCELED);
        assertThat(pear.isCanceled()).isFalse();
        assertThat(a.getStatus()).isEqualTo(DeliveryStatus.REQUESTED);
        assertThat(milk.isCanceled()).isFalse();
        verifyResult(new SellerResult(SELLER_A, true, null),
                new SellerResult(SELLER_B, false, "409-DELIVERY_NOT_CANCELABLE"));
    }

    @Test
    @DisplayName("요청 뒤에 배송준비가 됐으면 요청 시각 기준으로 취소한다")
    void handle_preparedAfterRequest_cancels() {
        Delivery b = delivery(SELLER_B);
        DeliveryItem milk = DeliveryItem.create(b, 21L, "우유", 1000L, 1L);
        b.prepare("CJ", "123");
        given(false, List.of(b));

        useCase.handle(request(BEFORE_PREPARE, item(21L, SELLER_B)));

        assertThat(milk.isCanceled()).isTrue();
        assertThat(b.getStatus()).isEqualTo(DeliveryStatus.CANCELED);
        verifyResult(new SellerResult(SELLER_B, true, null));
    }

    @Test
    @DisplayName("판매자 배송이 아직 없으면 상품 메모를 남기고 취소됨으로 답한다")
    void handle_noDelivery_memosAndReportsCanceled() {
        given(false, List.of());

        useCase.handle(request(BEFORE_PREPARE, item(11L, SELLER_A)));

        verify(deliveryOrderStates).markItemsCanceled(ORDER_ID, PurchaseType.INDIVIDUAL_GROUP, Set.of(11L));
        verifyResult(new SellerResult(SELLER_A, true, null));
    }

    @Test
    @DisplayName("같은 요청을 다시 받아도 같은 결과를 돌려주고 처음 취소 기록을 그대로 둔다")
    void handle_twice_sameResult() {
        Delivery a = delivery(SELLER_A);
        DeliveryItem apple = DeliveryItem.create(a, 11L, "사과", 1000L, 1L);
        DeliveryItem.create(a, 12L, "배", 1000L, 1L);
        given(false, List.of(a));

        useCase.handle(request(BEFORE_PREPARE, item(11L, SELLER_A)));
        LocalDateTime firstCanceledAt = apple.getCanceledAt();
        useCase.handle(request(BEFORE_PREPARE, item(11L, SELLER_A)));

        assertThat(apple.getCanceledAt()).isEqualTo(firstCanceledAt);
        verify(eventPublisher, times(2)).publish(new DeliveryBuyerCancelResultEvent(
                REQUEST_ID, ORDER_ID, List.of(new SellerResult(SELLER_A, true, null))));
    }

    @Test
    @DisplayName("주문 전체가 이미 취소됐으면 배송을 보지 않고 모든 판매자를 취소됨으로 답한다")
    void handle_orderCanceled_reportsAllCanceled() {
        given(true, List.of());

        useCase.handle(request(BEFORE_PREPARE, item(11L, SELLER_A), item(21L, SELLER_B)));

        verify(deliveryRepository, never()).findAllByOrderIdAndPurchaseTypeForUpdate(anyLong(), any());
        verifyResult(new SellerResult(SELLER_A, true, null), new SellerResult(SELLER_B, true, null));
    }
}
