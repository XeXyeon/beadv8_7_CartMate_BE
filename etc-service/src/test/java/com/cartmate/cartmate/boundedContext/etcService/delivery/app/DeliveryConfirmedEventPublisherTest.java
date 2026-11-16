package com.cartmate.cartmate.boundedContext.etcService.delivery.app;

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
        when(claimItemRepository.sumQuantityByDeliveryItem(1L, ClaimType.REFUND, ClaimStatus.COMPLETED))
                .thenReturn(List.of());

        publisher.publish(delivery);

        ArgumentCaptor<DeliveryConfirmedEvent> event = ArgumentCaptor.forClass(DeliveryConfirmedEvent.class);
        verify(eventPublisher).publish(event.capture());
        assertThat(event.getValue().items()).extracting(DeliveryConfirmedEvent.Item::orderItemId)
                .containsExactly(12L);
    }
}
