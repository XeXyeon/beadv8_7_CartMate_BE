package com.cartmate.cartmate.boundedContext.etcService.delivery.app;

import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimItemResult;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimStatus;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimType;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.Delivery;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryItem;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryClaimItemRepository;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryItemRepository;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import com.cartmate.cartmate.shared.delivery.event.DeliveryConfirmedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryConfirmedEventPublisherTest {

    private final DeliveryItemRepository deliveryItemRepository = mock(DeliveryItemRepository.class);
    private final DeliveryClaimItemRepository claimItemRepository = mock(DeliveryClaimItemRepository.class);
    private final DomainEventPublisher eventPublisher = mock(DomainEventPublisher.class);
    private final DeliveryConfirmedEventPublisher publisher =
            new DeliveryConfirmedEventPublisher(deliveryItemRepository, claimItemRepository, eventPublisher);

    @Test
    @DisplayName("배송 전에 취소된 상품은 구매확정 이벤트에서 뺀다")
    void publish_excludesCanceledItems() {
        Delivery delivery = Delivery.create(7L, 1007L, 100L, "O-100", PurchaseType.INDIVIDUAL_GROUP,
                "수령인", "010", "00000", "서울", "101호", "key-1", "hash");
        ReflectionTestUtils.setField(delivery, "id", 1L);
        DeliveryItem apple = DeliveryItem.create(delivery, 11L, "사과", 1000L, 3L);
        DeliveryItem pear = DeliveryItem.create(delivery, 12L, "배", 2000L, 1L);
        delivery.cancelItems(Set.of(11L), DeliveryCancelReason.SELLER_OUT_OF_STOCK, LocalDateTime.now());
        when(deliveryItemRepository.findByDeliveryId(1L)).thenReturn(List.of(apple, pear));
        when(claimItemRepository.sumQuantityByDeliveryItem(1L, ClaimType.REFUND, ClaimStatus.COMPLETED, ClaimItemResult.APPROVED))
                .thenReturn(List.of());

        publisher.publish(delivery);

        ArgumentCaptor<DeliveryConfirmedEvent> event = ArgumentCaptor.forClass(DeliveryConfirmedEvent.class);
        verify(eventPublisher).publish(event.capture());
        assertThat(event.getValue().items()).extracting(DeliveryConfirmedEvent.Item::orderItemId)
                .containsExactly(12L);
    }

    @Test
    @DisplayName("승인된 반품 수량만 구매확정 이벤트의 상품 수량에서 뺀다 (사과 3개 중 2개 반품 승인 → 1개)")
    void publish_subtractsApprovedReturnQuantityOnly() {
        Delivery delivery = Delivery.create(7L, 1007L, 100L, "O-100", PurchaseType.INDIVIDUAL_GROUP,
                "수령인", "010", "00000", "서울", "101호", "key-1", "hash");
        ReflectionTestUtils.setField(delivery, "id", 1L);
        DeliveryItem apple = DeliveryItem.create(delivery, 11L, "사과", 1000L, 3L);
        ReflectionTestUtils.setField(apple, "id", 501L);
        DeliveryItem pear = DeliveryItem.create(delivery, 12L, "배", 2000L, 1L);
        ReflectionTestUtils.setField(pear, "id", 502L);
        DeliveryClaimItemRepository.ClaimedQuantity returnedApples = mock(DeliveryClaimItemRepository.ClaimedQuantity.class);
        when(returnedApples.getDeliveryItemId()).thenReturn(501L);
        when(returnedApples.getQuantity()).thenReturn(2L);
        when(deliveryItemRepository.findByDeliveryId(1L)).thenReturn(List.of(apple, pear));
        when(claimItemRepository.sumQuantityByDeliveryItem(1L, ClaimType.REFUND, ClaimStatus.COMPLETED, ClaimItemResult.APPROVED))
                .thenReturn(List.of(returnedApples));

        publisher.publish(delivery);

        ArgumentCaptor<DeliveryConfirmedEvent> event = ArgumentCaptor.forClass(DeliveryConfirmedEvent.class);
        verify(eventPublisher).publish(event.capture());
        assertThat(event.getValue().items())
                .extracting(DeliveryConfirmedEvent.Item::orderItemId, DeliveryConfirmedEvent.Item::quantity)
                .containsExactly(tuple(11L, 1L), tuple(12L, 1L));
    }

    @Test
    @DisplayName("쿠폰 할인은 남은 수량 비율만큼(원 미만 버림) 보낸다 (사과 3개 할인 1000원 중 2개 반품 → 333원)")
    void publish_prorationsCouponDiscountByRemainingQuantity() {
        Delivery delivery = Delivery.create(7L, 1007L, 100L, "O-100", PurchaseType.INDIVIDUAL_GROUP,
                "수령인", "010", "00000", "서울", "101호", "key-1", "hash");
        ReflectionTestUtils.setField(delivery, "id", 1L);
        DeliveryItem apple = DeliveryItem.create(delivery, 11L, "사과", 1000L, 3L, 1000L, null);
        ReflectionTestUtils.setField(apple, "id", 501L);
        DeliveryItem pear = DeliveryItem.create(delivery, 12L, "배", 2000L, 1L, null, 700L);
        ReflectionTestUtils.setField(pear, "id", 502L);
        DeliveryClaimItemRepository.ClaimedQuantity returnedApples = mock(DeliveryClaimItemRepository.ClaimedQuantity.class);
        when(returnedApples.getDeliveryItemId()).thenReturn(501L);
        when(returnedApples.getQuantity()).thenReturn(2L);
        when(deliveryItemRepository.findByDeliveryId(1L)).thenReturn(List.of(apple, pear));
        when(claimItemRepository.sumQuantityByDeliveryItem(1L, ClaimType.REFUND, ClaimStatus.COMPLETED, ClaimItemResult.APPROVED))
                .thenReturn(List.of(returnedApples));

        publisher.publish(delivery);

        ArgumentCaptor<DeliveryConfirmedEvent> event = ArgumentCaptor.forClass(DeliveryConfirmedEvent.class);
        verify(eventPublisher).publish(event.capture());
        assertThat(event.getValue().items())
                .extracting(DeliveryConfirmedEvent.Item::orderItemId,
                        DeliveryConfirmedEvent.Item::sellerDiscountAmount,
                        DeliveryConfirmedEvent.Item::platformDiscountAmount)
                .containsExactly(tuple(11L, 333L, 0L), tuple(12L, 0L, 700L));
    }
}
