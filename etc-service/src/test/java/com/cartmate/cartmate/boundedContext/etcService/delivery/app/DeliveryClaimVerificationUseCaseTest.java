package com.cartmate.cartmate.boundedContext.etcService.delivery.app;

import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimReason;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimStatus;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimType;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryClaim;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryClaimRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeliveryClaimVerificationUseCaseTest {

    private static final long CLAIM_ID = 10L;

    private final DeliveryClaimRepository claimRepository = mock(DeliveryClaimRepository.class);
    private final DeliveryClaimVerificationUseCase useCase = new DeliveryClaimVerificationUseCase(claimRepository);

    private DeliveryClaim verifyingClaim() {
        DeliveryClaim claim = DeliveryClaim.create(null, 1L, ClaimType.REFUND, ClaimReason.SIMPLE_CHANGE, null);
        when(claimRepository.findByIdForUpdate(CLAIM_ID)).thenReturn(Optional.of(claim));
        return claim;
    }

    @Test
    @DisplayName("접수 검사를 통과하면 확인 중인 클레임이 접수가 된다")
    void verified_accepted_becomesRequested() {
        DeliveryClaim claim = verifyingClaim();

        useCase.apply(CLAIM_ID, true, null);

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REQUESTED);
    }

    @Test
    @DisplayName("접수 검사에서 거절되면 접수 불가가 되고 이유 코드가 남는다")
    void verified_rejected_becomesNotAccepted() {
        DeliveryClaim claim = verifyingClaim();

        useCase.apply(CLAIM_ID, false, "409-ORDER_RETURN_COUPON_MINIMUM");

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.NOT_ACCEPTED);
        assertThat(claim.getNotAcceptedCode()).isEqualTo("409-ORDER_RETURN_COUPON_MINIMUM");
    }

    @Test
    @DisplayName("이미 접수된 클레임에 늦게 온 거절 결과는 무시한다")
    void verified_twice_isIgnored() {
        DeliveryClaim claim = verifyingClaim();
        useCase.apply(CLAIM_ID, true, null);

        useCase.apply(CLAIM_ID, false, "409-ORDER_RETURN_COUPON_MINIMUM");

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REQUESTED);
        assertThat(claim.getNotAcceptedCode()).isNull();
    }

    @Test
    @DisplayName("없는 클레임의 검사 결과는 오류로 올려 다시 처리하게 한다")
    void verified_unknownClaim_throws() {
        when(claimRepository.findByIdForUpdate(CLAIM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.apply(CLAIM_ID, true, null))
                .hasMessageContaining("DELIVERY_CLAIM_NOT_FOUND");
    }
}
