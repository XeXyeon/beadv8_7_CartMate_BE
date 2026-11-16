package com.cartmate.cartmate.boundedContext.etcService.delivery.app;

import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.DeliveryCreateCommand;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.Delivery;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryItem;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryItemOptionRepository;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryItemRepository;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryRepository;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryCreateUseCaseTest {

    private static final long ORDER_ID = 100L;
    private static final long SELLER_ID = 1007L;

    private final DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
    private final DeliveryItemRepository deliveryItemRepository = mock(DeliveryItemRepository.class);
    private final DeliveryItemOptionRepository optionRepository = mock(DeliveryItemOptionRepository.class);
    private final DeliveryOrderStates deliveryOrderStates = mock(DeliveryOrderStates.class);
    private final DeliveryCreateUseCase useCase = new DeliveryCreateUseCase(
            deliveryRepository, deliveryItemRepository, optionRepository, deliveryOrderStates);

    private DeliveryCreateCommand command() {
        return new DeliveryCreateCommand(7L, SELLER_ID, ORDER_ID, "O-100", PurchaseType.INDIVIDUAL_GROUP,
                "수령인", "010", "00000", "서울", "101호", "ORDER-PAID-100-1007",
                List.of(new DeliveryCreateCommand.Item(11L, "사과", 1000L, 3L, List.of()),
                        new DeliveryCreateCommand.Item(12L, "배", 2000L, 1L, List.of())));
    }

    private void given(boolean accepted, Set<Long> memoed) {
        when(deliveryOrderStates.acceptCreation(ORDER_ID, PurchaseType.INDIVIDUAL_GROUP, SELLER_ID)).thenReturn(accepted);
        when(deliveryOrderStates.memoedOrderItemIds(ORDER_ID, PurchaseType.INDIVIDUAL_GROUP)).thenReturn(memoed);
        when(deliveryRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("배송 생성 전에 취소 메모된 상품은 빼고 배송을 만든다")
    void create_skipsMemoedItems() {
        given(true, Set.of(11L));

        useCase.create(command());

        verify(deliveryRepository).save(any(Delivery.class));
        ArgumentCaptor<DeliveryItem> saved = ArgumentCaptor.forClass(DeliveryItem.class);
        verify(deliveryItemRepository).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(DeliveryItem::getOrderItemId).containsExactly(12L);
    }

    @Test
    @DisplayName("판매자 상품이 모두 취소 메모돼 있으면 배송을 만들지 않는다")
    void create_skipsDeliveryWhenAllItemsMemoed() {
        given(true, Set.of(11L, 12L));

        useCase.create(command());

        verify(deliveryRepository, never()).save(any());
        verify(deliveryItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("주문이 취소됐으면 메모를 보지 않고 배송을 만들지 않는다")
    void create_orderCanceled_skips() {
        given(false, Set.of());

        useCase.create(command());

        verify(deliveryOrderStates, never()).memoedOrderItemIds(anyLong(), any());
        verify(deliveryRepository, never()).save(any());
    }
}
