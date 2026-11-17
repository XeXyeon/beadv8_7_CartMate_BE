package com.cartmate.cartmate.boundedContext.etcService.delivery.app;

import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.DeliveryClaimCreateCommand;
import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.DeliveryOrderRef;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimReason;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimType;
import com.cartmate.cartmate.global.lock.DistributedLockExecutor;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryClaimLockServiceTest {

    private static final long DELIVERY_ID = 1L;
    private static final long ORDER_ID = 100L;

    private final DeliveryFacade deliveryFacade = mock(DeliveryFacade.class);
    private final DistributedLockExecutor lockExecutor = mock(DistributedLockExecutor.class);
    private final DeliveryClaimLockService lockService = new DeliveryClaimLockService(deliveryFacade, lockExecutor);

    // 락을 잡은 시점과 접수가 실행된 시점을 순서대로 기록한다
    private final List<String> events = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(lockExecutor.get(anyString(), any())).thenAnswer(invocation -> {
            events.add("lock:" + invocation.getArgument(0));
            return ((Supplier<Object>) invocation.getArgument(1)).get();
        });
        when(deliveryFacade.createClaim(any())).thenAnswer(invocation -> {
            events.add("create");
            return null;
        });
        when(deliveryFacade.getDeliveryOrderRef(DELIVERY_ID))
                .thenReturn(new DeliveryOrderRef(ORDER_ID, PurchaseType.INDIVIDUAL_GROUP));
    }

    private DeliveryClaimCreateCommand command(Long deliveryId, ClaimType type) {
        return new DeliveryClaimCreateCommand(deliveryId, 7L, type, ClaimReason.SIMPLE_CHANGE, null,
                List.of(new DeliveryClaimCreateCommand.Item(11L)));
    }

    @Test
    @DisplayName("반품 접수는 같은 주문의 다른 배송 접수와 순서를 맞추려고 주문 락을 먼저 잡고 접수한다")
    void create_refund_locksOrderBeforeCreating() {
        lockService.create(command(DELIVERY_ID, ClaimType.REFUND));

        assertThat(events).containsExactly("lock:claim-order:INDIVIDUAL_GROUP:100", "create");
    }

    @Test
    @DisplayName("교환 접수는 환불 계산에 영향을 주지 않아 주문 락 없이 접수한다")
    void create_exchange_skipsOrderLock() {
        lockService.create(command(DELIVERY_ID, ClaimType.EXCHANGE));

        assertThat(events).containsExactly("create");
        verify(lockExecutor, never()).get(anyString(), any());
    }

    @Test
    @DisplayName("배송 ID가 없는 요청은 락 없이 접수 쪽 검증이 거절하게 한다")
    void create_withoutDeliveryId_skipsOrderLock() {
        lockService.create(command(null, ClaimType.REFUND));

        assertThat(events).containsExactly("create");
        verify(deliveryFacade, never()).getDeliveryOrderRef(anyLong());
    }
}
