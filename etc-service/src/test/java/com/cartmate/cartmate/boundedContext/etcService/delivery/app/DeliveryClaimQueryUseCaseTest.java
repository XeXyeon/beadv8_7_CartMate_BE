package com.cartmate.cartmate.boundedContext.etcService.delivery.app;

import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimStatus;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryClaim;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryClaimItemRepository;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryClaimRepository;
import com.cartmate.cartmate.shared.member.SellerQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryClaimQueryUseCaseTest {

    private static final long MEMBER_ID = 7L;
    private static final long SELLER_ID = 70L;
    private static final Pageable PAGE = PageRequest.of(0, 20);

    private final DeliveryClaimRepository claimRepository = mock(DeliveryClaimRepository.class);
    private final DeliveryClaimItemRepository claimItemRepository = mock(DeliveryClaimItemRepository.class);
    private final SellerQuery sellerQuery = mock(SellerQuery.class);
    private final DeliveryClaimQueryUseCase useCase =
            new DeliveryClaimQueryUseCase(claimRepository, claimItemRepository, sellerQuery);

    private void sellerFound() {
        when(sellerQuery.findSellerIdByMemberId(MEMBER_ID)).thenReturn(Optional.of(SELLER_ID));
    }

    @SuppressWarnings("unchecked")
    private Collection<ClaimStatus> queriedStatuses() {
        ArgumentCaptor<Collection<ClaimStatus>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(claimRepository).findSellerClaims(eq(SELLER_ID), captor.capture(), anyCollection(), eq(PAGE));
        return captor.getValue();
    }

    @Test
    @DisplayName("판매자 목록은 상태를 주지 않아도 확인 중과 접수 불가 클레임을 조회하지 않는다")
    void sellerClaims_defaultHidesVerifyingAndNotAccepted() {
        sellerFound();
        when(claimRepository.findSellerClaims(eq(SELLER_ID), anyCollection(), anyCollection(), eq(PAGE)))
                .thenReturn(Page.empty());

        useCase.getSellerClaims(MEMBER_ID, null, null, PAGE);

        assertThat(queriedStatuses()).containsExactlyInAnyOrder(
                ClaimStatus.REQUESTED, ClaimStatus.APPROVED, ClaimStatus.REJECTED, ClaimStatus.COMPLETED);
    }

    @Test
    @DisplayName("판매자가 숨긴 상태를 함께 지정해도 그 상태는 빼고 조회한다")
    void sellerClaims_explicitHiddenStatusIsDropped() {
        sellerFound();
        when(claimRepository.findSellerClaims(eq(SELLER_ID), anyCollection(), anyCollection(), eq(PAGE)))
                .thenReturn(Page.empty());

        useCase.getSellerClaims(MEMBER_ID, List.of(ClaimStatus.REQUESTED, ClaimStatus.VERIFYING), null, PAGE);

        assertThat(queriedStatuses()).containsExactly(ClaimStatus.REQUESTED);
    }

    @Test
    @DisplayName("숨긴 상태만 지정하면 저장소를 조회하지 않고 빈 목록을 돌려준다")
    void sellerClaims_onlyHiddenStatuses_returnsEmptyWithoutQuery() {
        sellerFound();

        Page<?> page = useCase.getSellerClaims(MEMBER_ID, List.of(ClaimStatus.VERIFYING, ClaimStatus.NOT_ACCEPTED), null, PAGE);

        assertThat(page.getContent()).isEmpty();
        verify(claimRepository, never()).findSellerClaims(any(), anyCollection(), anyCollection(), any());
    }

    @Test
    @DisplayName("구매자는 자기 클레임을 확인 중·접수 불가 상태까지 그대로 본다")
    void myClaims_showsAllStatuses() {
        DeliveryClaim claim = mock(DeliveryClaim.class);
        when(claimRepository.findByMemberIdOrderByCreatedAtDescIdDesc(MEMBER_ID, PAGE)).thenReturn(Page.empty());

        useCase.getMyClaims(MEMBER_ID, PAGE);

        verify(claimRepository, never()).findSellerClaims(any(), anyCollection(), anyCollection(), any());
        verify(claimRepository).findByMemberIdOrderByCreatedAtDescIdDesc(MEMBER_ID, PAGE);
    }
}
