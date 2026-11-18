package com.cartmate.cartmate.boundedContext.etcService.delivery.app;

import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimReason;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimType;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.Delivery;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryClaim;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryClaimItemRepository;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryClaimRepository;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.shared.delivery.event.DeliveryClaimCompletedEvent;
import com.cartmate.cartmate.shared.member.SellerQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryClaimCompleteUseCaseTest {

    private static final long CLAIM_ID = 10L;
    private static final long SELLER_MEMBER_ID = 7L;
    private static final long SELLER_ID = 70L;

    private final DeliveryClaimRepository claimRepository = mock(DeliveryClaimRepository.class);
    private final DeliveryClaimItemRepository claimItemRepository = mock(DeliveryClaimItemRepository.class);
    private final SellerQuery sellerQuery = mock(SellerQuery.class);
    private final DomainEventPublisher eventPublisher = mock(DomainEventPublisher.class);
    private final DeliveryClaimCompleteUseCase useCase =
            new DeliveryClaimCompleteUseCase(claimRepository, claimItemRepository, sellerQuery, eventPublisher);

    @ParameterizedTest
    @CsvSource({"PRODUCT_DEFECT, true", "SIMPLE_CHANGE, false"})
    @DisplayName("처리 완료 이벤트에 사유가 판매자 탓인지 함께 싣는다")
    void completedEventCarriesSellerFault(ClaimReason reason, boolean sellerFault) {
        Delivery delivery = mock(Delivery.class);
        when(delivery.getSellerId()).thenReturn(SELLER_ID);
        DeliveryClaim claim = DeliveryClaim.create(delivery, 1L, ClaimType.EXCHANGE, reason, null);
        ReflectionTestUtils.setField(claim, "id", CLAIM_ID);
        claim.decide(true);
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim));
        when(sellerQuery.isSellerOwner(SELLER_MEMBER_ID, SELLER_ID)).thenReturn(true);
        when(claimItemRepository.findAllWithDeliveryItemByClaimIds(anyList()))
                .thenReturn(List.of());

        useCase.complete(CLAIM_ID, SELLER_MEMBER_ID);

        ArgumentCaptor<DeliveryClaimCompletedEvent> captor = ArgumentCaptor.forClass(DeliveryClaimCompletedEvent.class);
        verify(eventPublisher).publish(captor.capture());
        assertThat(captor.getValue().sellerFault()).isEqualTo(sellerFault);
    }
}
