package com.cartmate.cartmate.boundedContext.etcService.trust.app;

import com.cartmate.cartmate.boundedContext.etcService.trust.out.SellerTrustHistoryRepository;
import com.cartmate.cartmate.shared.delivery.event.DeliveryClaimCompletedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SellerTrustHistoryRecordUseCaseTest {

    private static final LocalDateTime COMPLETED_AT = LocalDateTime.of(2026, 11, 17, 12, 0);

    private final SellerTrustHistoryRepository repository = mock(SellerTrustHistoryRepository.class);
    private final SellerTrustHistoryRecordUseCase useCase = new SellerTrustHistoryRecordUseCase(repository);

    @Test
    @DisplayName("판매자 탓으로 완료된 클레임은 귀책 이력으로 남긴다")
    void recordsSellerFaultClaim() {
        useCase.recordClaimCompleted(event("EXCHANGE", "WRONG_DELIVERY", true));

        verify(repository).save(any());
    }

    @Test
    @DisplayName("판매자 탓이 아닌 클레임은 남기지 않는다")
    void skipsBuyerReasonClaim() {
        useCase.recordClaimCompleted(event("REFUND", "SIMPLE_CHANGE", false));

        verify(repository, never()).save(any());
    }

    private DeliveryClaimCompletedEvent event(String type, String reason, boolean sellerFault) {
        return new DeliveryClaimCompletedEvent(1L, 2L, 3L, type, reason, sellerFault, COMPLETED_AT);
    }
}
