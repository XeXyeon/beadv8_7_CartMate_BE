package com.cartmate.cartmate.boundedContext.etcService.delivery.app;

import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryOrderState;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryItemCancelMemoRepository;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryOrderStateRepository;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryOrderStatesTest {

    private static final long ORDER_ID = 100L;

    private final DeliveryOrderStateRepository stateRepository = mock(DeliveryOrderStateRepository.class);
    private final DeliveryItemCancelMemoRepository itemCancelMemoRepository = mock(DeliveryItemCancelMemoRepository.class);
    private final DeliveryOrderState state = mock(DeliveryOrderState.class);
    private final DeliveryOrderStates states =
            new DeliveryOrderStates(stateRepository, itemCancelMemoRepository);

    private void givenOrderState(boolean canceled) {
        when(stateRepository.insertIfAbsent(anyLong(), anyString(), anyString(), any())).thenReturn(0);
        when(stateRepository.findForUpdate(ORDER_ID, PurchaseType.INDIVIDUAL_GROUP)).thenReturn(Optional.of(state));
        when(state.isCanceled()).thenReturn(canceled);
    }

    @Test
    @DisplayName("주문이 취소되지 않았으면 배송을 만든다")
    void acceptsCreation() {
        givenOrderState(false);

        assertThat(states.acceptCreation(ORDER_ID, PurchaseType.INDIVIDUAL_GROUP)).isTrue();
    }

    @Test
    @DisplayName("주문 전체가 취소됐으면 배송을 만들지 않는다")
    void rejectsCreationOfCanceledOrder() {
        givenOrderState(true);

        assertThat(states.acceptCreation(ORDER_ID, PurchaseType.INDIVIDUAL_GROUP)).isFalse();
    }

    @Test
    @DisplayName("취소된 주문 항목마다 메모를 남긴다")
    void marksEachItemCanceled() {
        states.markItemsCanceled(ORDER_ID, PurchaseType.SHARED_GROUP, List.of(11L, 12L));

        verify(itemCancelMemoRepository).insertIfAbsent(eq("SHARED_GROUP"), eq(ORDER_ID), eq(11L), any());
        verify(itemCancelMemoRepository).insertIfAbsent(eq("SHARED_GROUP"), eq(ORDER_ID), eq(12L), any());
    }
}
