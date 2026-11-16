package com.cartmate.cartmate.boundedContext.etcService.delivery.app;

import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.DeliveryResponse;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.Delivery;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryItem;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryStatus;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryRepository;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import com.cartmate.cartmate.shared.delivery.event.DeliverySellerCanceledEvent;
import com.cartmate.cartmate.shared.member.SellerQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliverySellerReasonCancelUseCaseTest {

    private static final long DELIVERY_ID = 1L;
    private static final long MEMBER_ID = 70L;
    private static final long SELLER_ID = 1007L;
    private static final long ORDER_ID = 100L;
    private static final long APPLE_ID = 501L;
    private static final long PEAR_ID = 502L;

    private final DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
    private final SellerQuery sellerQuery = mock(SellerQuery.class);
    private final DomainEventPublisher eventPublisher = mock(DomainEventPublisher.class);
    private final DeliverySellerReasonCancelUseCase useCase =
            new DeliverySellerReasonCancelUseCase(deliveryRepository, sellerQuery, eventPublisher);

    private DeliveryItem apple;
    private DeliveryItem pear;

    private Delivery givenDelivery(PurchaseType purchaseType, boolean owner) {
        Delivery delivery = Delivery.create(7L, SELLER_ID, ORDER_ID, null, purchaseType,
                "수령인", "010", "00000", "서울", "101호", "key-1", "hash");
        apple = item(delivery, APPLE_ID, 11L, "사과");
        pear = item(delivery, PEAR_ID, 12L, "배");
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));
        when(sellerQuery.isSellerOwner(MEMBER_ID, SELLER_ID)).thenReturn(owner);
        return delivery;
    }

    private DeliveryItem item(Delivery delivery, long id, long orderItemId, String name) {
        DeliveryItem item = DeliveryItem.create(delivery, orderItemId, name, 1000L, 1L);
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    @Test
    @DisplayName("일부 상품만 취소하면 그 주문 항목만 담아 주문 서버에 알리고 배송은 진행한다")
    void sellerCancel_someItems_publishesEventWithThoseOrderItemIds() {
        Delivery delivery = givenDelivery(PurchaseType.SHARED_GROUP, true);

        DeliveryResponse response = useCase.cancel(DELIVERY_ID, MEMBER_ID,
                DeliveryCancelReason.SELLER_OUT_OF_STOCK, List.of(APPLE_ID));

        assertThat(response.status()).isEqualTo(DeliveryStatus.REQUESTED);
        assertThat(pear.isCanceled()).isFalse();
        verify(eventPublisher).publish(new DeliverySellerCanceledEvent(
                PurchaseType.SHARED_GROUP, ORDER_ID, SELLER_ID,
                DeliveryCancelReason.SELLER_OUT_OF_STOCK, apple.getCanceledAt(), List.of(11L)));
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.REQUESTED);
    }

    @Test
    @DisplayName("상품을 전부 고르면 배송도 취소하고 모든 주문 항목을 담아 알린다")
    void sellerCancel_allItems_cancelsDelivery() {
        Delivery delivery = givenDelivery(PurchaseType.INDIVIDUAL_GROUP, true);

        DeliveryResponse response = useCase.cancel(DELIVERY_ID, MEMBER_ID,
                DeliveryCancelReason.SELLER_OUT_OF_STOCK, List.of(APPLE_ID, PEAR_ID));

        assertThat(response.status()).isEqualTo(DeliveryStatus.CANCELED);
        verify(eventPublisher).publish(new DeliverySellerCanceledEvent(
                PurchaseType.INDIVIDUAL_GROUP, ORDER_ID, SELLER_ID,
                DeliveryCancelReason.SELLER_OUT_OF_STOCK, delivery.getCanceledAt(), List.of(11L, 12L)));
    }

    @Test
    @DisplayName("같은 상품을 같은 사유로 다시 누르면 성공하지만 이벤트는 다시 내지 않는다")
    void sellerCancel_sameItemsSameReason_noEvent() {
        givenDelivery(PurchaseType.INDIVIDUAL_GROUP, true);
        useCase.cancel(DELIVERY_ID, MEMBER_ID, DeliveryCancelReason.SELLER_OUT_OF_STOCK, List.of(APPLE_ID));

        useCase.cancel(DELIVERY_ID, MEMBER_ID, DeliveryCancelReason.SELLER_OUT_OF_STOCK, List.of(APPLE_ID));

        verify(eventPublisher).publish(any());
    }

    @Test
    @DisplayName("다른 사유로 이미 취소된 상품이 섞이면 그 사유를 담아 이미 취소됐다고 알리고 아무것도 바꾸지 않는다")
    void sellerCancel_itemCanceledByOtherReason_throwsAlreadyCanceled() {
        givenDelivery(PurchaseType.INDIVIDUAL_GROUP, true);
        useCase.cancel(DELIVERY_ID, MEMBER_ID, DeliveryCancelReason.SELLER_OUT_OF_STOCK, List.of(APPLE_ID));

        assertThatThrownBy(() -> useCase.cancel(DELIVERY_ID, MEMBER_ID,
                DeliveryCancelReason.SELLER_OTHER, List.of(APPLE_ID, PEAR_ID)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("DELIVERY_ALREADY_CANCELED")
                .hasMessageContaining("(사유: 품절·재고 부족)");
        assertThat(pear.isCanceled()).isFalse();
    }

    @Test
    @DisplayName("이 배송의 상품이 아니면 찾을 수 없다고 알린다")
    void sellerCancel_itemOfOtherDelivery_throwsNotFound() {
        givenDelivery(PurchaseType.INDIVIDUAL_GROUP, true);

        assertThatThrownBy(() -> useCase.cancel(DELIVERY_ID, MEMBER_ID,
                DeliveryCancelReason.SELLER_OTHER, List.of(APPLE_ID, 999L)))
                .hasMessageContaining("DELIVERY_ITEM_NOT_FOUND");
        assertThat(apple.isCanceled()).isFalse();
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("다른 판매자의 배송은 취소할 수 없다")
    void otherSellerIsDenied() {
        Delivery delivery = givenDelivery(PurchaseType.INDIVIDUAL_GROUP, false);

        assertThatThrownBy(() -> useCase.cancel(DELIVERY_ID, MEMBER_ID,
                DeliveryCancelReason.SELLER_OTHER, List.of(APPLE_ID)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("DELIVERY_ACCESS_DENIED");
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.REQUESTED);
        verify(eventPublisher, never()).publish(any());
    }
}
