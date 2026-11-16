package com.cartmate.cartmate.boundedContext.etcService.delivery.app;

import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.Delivery;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryOrderState;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryStatus;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryRepository;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import com.cartmate.cartmate.shared.delivery.SalesStopCancelResult;
import com.cartmate.cartmate.shared.delivery.event.DeliverySalesStopCancelResultEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryCancelUseCaseTest {

    private static final long ORDER_ID = 100L;
    private static final List<Long> ITEM_IDS = List.of(11L, 12L);

    private final DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
    private final DeliveryOrderStates deliveryOrderStates = mock(DeliveryOrderStates.class);
    private final DomainEventPublisher eventPublisher = mock(DomainEventPublisher.class);
    private final DeliveryOrderState state = mock(DeliveryOrderState.class);
    private final DeliveryCancelUseCase useCase =
            new DeliveryCancelUseCase(deliveryRepository, deliveryOrderStates, eventPublisher);

    private Delivery delivery(long sellerId) {
        return Delivery.create(7L, sellerId, ORDER_ID, "O-100", PurchaseType.INDIVIDUAL_GROUP,
                "수령인", "010", "00000", "서울", "101호", "key-" + sellerId, "hash");
    }

    private void givenSalesStopState(PurchaseType purchaseType, boolean canceled, List<Delivery> deliveries) {
        when(state.isCanceled()).thenReturn(canceled);
        when(deliveryOrderStates.lockForSellerCancel(ORDER_ID, purchaseType))
                .thenReturn(new DeliveryOrderStates.Locked(state, false));
        when(deliveryRepository.findAllByOrderIdAndPurchaseTypeForUpdate(ORDER_ID, purchaseType)).thenReturn(deliveries);
    }

    private Delivery sharedDelivery(long sellerId) {
        return Delivery.create(7L, sellerId, ORDER_ID, null, PurchaseType.SHARED_GROUP,
                "수령인", "010", "00000", "서울", "101호", "shared-key-" + sellerId, "hash");
    }

    private Delivery shipped(Delivery delivery) {
        delivery.prepare("CJ", "123");
        delivery.ship();
        return delivery;
    }

    private void verifyResult(PurchaseType purchaseType, long sellerId, SalesStopCancelResult result, String failureCode) {
        verify(eventPublisher).publish(
                new DeliverySalesStopCancelResultEvent(purchaseType, ORDER_ID, sellerId, result, failureCode));
    }

    @Test
    @DisplayName("판매글 내림: 개인 주문은 그 판매자 배송만 취소하고 메모를 남긴 뒤 그 판매자 몫 취소됨을 보낸다")
    void salesStopCancelsOnlySellerOfPersonalOrder() {
        Delivery seller = delivery(1007L);
        Delivery other = delivery(1009L);
        givenSalesStopState(PurchaseType.INDIVIDUAL_GROUP, false, List.of(seller, other));

        useCase.cancelBySalesStop(PurchaseType.INDIVIDUAL_GROUP, ORDER_ID, 1007L, ITEM_IDS);

        assertThat(seller.getStatus()).isEqualTo(DeliveryStatus.CANCELED);
        assertThat(other.getStatus()).isEqualTo(DeliveryStatus.REQUESTED);
        verify(deliveryOrderStates).markItemsCanceled(ORDER_ID, PurchaseType.INDIVIDUAL_GROUP, ITEM_IDS);
        verify(state, never()).markCanceled();
        verifyResult(PurchaseType.INDIVIDUAL_GROUP, 1007L, SalesStopCancelResult.SELLER_CANCELED, null);
    }

    @Test
    @DisplayName("판매글 내림: 배송준비중이어도 요청 시각과 상관없이 취소한다")
    void salesStopCancelsPreparedDelivery() {
        Delivery prepared = delivery(1007L);
        prepared.prepare("CJ", "123");
        givenSalesStopState(PurchaseType.INDIVIDUAL_GROUP, false, List.of(prepared));

        useCase.cancelBySalesStop(PurchaseType.INDIVIDUAL_GROUP, ORDER_ID, 1007L, ITEM_IDS);

        assertThat(prepared.getStatus()).isEqualTo(DeliveryStatus.CANCELED);
        verifyResult(PurchaseType.INDIVIDUAL_GROUP, 1007L, SalesStopCancelResult.SELLER_CANCELED, null);
    }

    @Test
    @DisplayName("판매글 내림: 그 판매자 배송이 발송됐으면 취소하지 않고 거절한다")
    void salesStopRejectsShippedSeller() {
        Delivery seller = shipped(delivery(1007L));
        givenSalesStopState(PurchaseType.INDIVIDUAL_GROUP, false, List.of(seller));

        useCase.cancelBySalesStop(PurchaseType.INDIVIDUAL_GROUP, ORDER_ID, 1007L, ITEM_IDS);

        assertThat(seller.getStatus()).isEqualTo(DeliveryStatus.SHIPPING);
        verify(deliveryOrderStates, never()).markItemsCanceled(anyLong(), any(), any());
        verifyResult(PurchaseType.INDIVIDUAL_GROUP, 1007L, SalesStopCancelResult.REJECTED, "409-DELIVERY_NOT_CANCELABLE");
    }

    @Test
    @DisplayName("판매글 내림: 배송이 아직 없으면 메모만 남겨 나중에 만들지 않게 하고 취소됨을 보낸다")
    void cancelBySalesStop_sellerCanceled_memosGivenItems() {
        givenSalesStopState(PurchaseType.INDIVIDUAL_GROUP, false, List.of());

        useCase.cancelBySalesStop(PurchaseType.INDIVIDUAL_GROUP, ORDER_ID, 1007L, ITEM_IDS);

        verify(deliveryOrderStates).markItemsCanceled(ORDER_ID, PurchaseType.INDIVIDUAL_GROUP, ITEM_IDS);
        verifyResult(PurchaseType.INDIVIDUAL_GROUP, 1007L, SalesStopCancelResult.SELLER_CANCELED, null);
    }

    @Test
    @DisplayName("판매글 내림: 공동구매의 모든 배송이 취소 가능하면 전부 취소하고 전체 취소됨을 보낸다")
    void salesStopCancelsWholeSharedOrder() {
        Delivery seller = sharedDelivery(1007L);
        Delivery other = sharedDelivery(1009L);
        givenSalesStopState(PurchaseType.SHARED_GROUP, false, List.of(seller, other));

        useCase.cancelBySalesStop(PurchaseType.SHARED_GROUP, ORDER_ID, 1007L, ITEM_IDS);

        assertThat(seller.getStatus()).isEqualTo(DeliveryStatus.CANCELED);
        assertThat(other.getStatus()).isEqualTo(DeliveryStatus.CANCELED);
        verify(state).markCanceled();
        verifyResult(PurchaseType.SHARED_GROUP, 1007L, SalesStopCancelResult.WHOLE_CANCELED, null);
    }

    @Test
    @DisplayName("판매글 내림: 공동구매의 다른 판매자 배송이 발송됐으면 그 판매자 몫만 취소한다")
    void salesStopCancelsOnlySellerWhenOtherShipped() {
        Delivery seller = sharedDelivery(1007L);
        Delivery other = shipped(sharedDelivery(1009L));
        givenSalesStopState(PurchaseType.SHARED_GROUP, false, List.of(seller, other));

        useCase.cancelBySalesStop(PurchaseType.SHARED_GROUP, ORDER_ID, 1007L, ITEM_IDS);

        assertThat(seller.getStatus()).isEqualTo(DeliveryStatus.CANCELED);
        assertThat(other.getStatus()).isEqualTo(DeliveryStatus.SHIPPING);
        verify(state, never()).markCanceled();
        verify(deliveryOrderStates).markItemsCanceled(ORDER_ID, PurchaseType.SHARED_GROUP, ITEM_IDS);
        verifyResult(PurchaseType.SHARED_GROUP, 1007L, SalesStopCancelResult.SELLER_CANCELED, null);
    }

    @Test
    @DisplayName("판매글 내림: 주문 전체가 이미 취소됐으면 배송을 보지 않고 공동구매는 전체 취소됨을 다시 보낸다")
    void salesStopOnCanceledSharedOrderRepliesWhole() {
        givenSalesStopState(PurchaseType.SHARED_GROUP, true, List.of());

        useCase.cancelBySalesStop(PurchaseType.SHARED_GROUP, ORDER_ID, 1007L, ITEM_IDS);

        verify(deliveryRepository, never()).findAllByOrderIdAndPurchaseTypeForUpdate(anyLong(), any());
        verifyResult(PurchaseType.SHARED_GROUP, 1007L, SalesStopCancelResult.WHOLE_CANCELED, null);
    }
}
