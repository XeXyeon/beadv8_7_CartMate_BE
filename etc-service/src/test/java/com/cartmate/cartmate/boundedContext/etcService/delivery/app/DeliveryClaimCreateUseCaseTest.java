package com.cartmate.cartmate.boundedContext.etcService.delivery.app;

import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.DeliveryClaimCreateCommand;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimReason;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimType;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.Delivery;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryItem;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryClaimItemRepository;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryClaimRepository;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryItemRepository;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryRepository;
import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryClaimCreateUseCaseTest {

    private static final long DELIVERY_ID = 1L;
    private static final long MEMBER_ID = 7L;
    private static final long APPLE_ID = 501L;
    private static final long PEAR_ID = 502L;

    private final DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
    private final DeliveryItemRepository deliveryItemRepository = mock(DeliveryItemRepository.class);
    private final DeliveryClaimRepository claimRepository = mock(DeliveryClaimRepository.class);
    private final DeliveryClaimItemRepository claimItemRepository = mock(DeliveryClaimItemRepository.class);
    private final DeliveryClaimCreateUseCase useCase = new DeliveryClaimCreateUseCase(
            deliveryRepository, deliveryItemRepository, claimRepository, claimItemRepository);

    @Test
    @DisplayName("배송 전에 취소된 상품은 교환·환불을 접수할 수 없다")
    void claim_rejectsCanceledItem() {
        Delivery delivery = Delivery.create(MEMBER_ID, 1007L, 100L, "O-100", PurchaseType.INDIVIDUAL_GROUP,
                "수령인", "010", "00000", "서울", "101호", "key-1", "hash");
        DeliveryItem apple = item(delivery, APPLE_ID, 11L);
        DeliveryItem pear = item(delivery, PEAR_ID, 12L);
        delivery.cancelItems(Set.of(11L), DeliveryCancelReason.BUYER_CANCELED, LocalDateTime.now());
        delivery.prepare("CJ", "123");
        delivery.ship();
        delivery.deliver();
        when(deliveryRepository.findByIdForClaim(DELIVERY_ID)).thenReturn(Optional.of(delivery));
        when(deliveryItemRepository.findByDeliveryId(DELIVERY_ID)).thenReturn(List.of(apple, pear));

        assertThatThrownBy(() -> useCase.create(new DeliveryClaimCreateCommand(DELIVERY_ID, MEMBER_ID,
                ClaimType.EXCHANGE, ClaimReason.PRODUCT_DEFECT, null,
                List.of(new DeliveryClaimCreateCommand.Item(APPLE_ID, 1L)))))
                .hasMessageContaining("DELIVERY_CLAIM_ITEM_CANCELED");
        verify(claimRepository, never()).save(any());
    }

    private DeliveryItem item(Delivery delivery, long id, long orderItemId) {
        DeliveryItem item = DeliveryItem.create(delivery, orderItemId, "상품", 1000L, 1L);
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }
}
