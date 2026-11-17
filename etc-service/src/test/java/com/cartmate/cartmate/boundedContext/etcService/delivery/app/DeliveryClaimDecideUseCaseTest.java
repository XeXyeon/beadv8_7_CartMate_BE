package com.cartmate.cartmate.boundedContext.etcService.delivery.app;

import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.DeliveryClaimDecideRequest;
import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.SellerClaimResponse;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimItemResult;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimReason;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimStatus;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimType;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.Delivery;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryClaim;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryClaimItem;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryItem;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.RefundStatus;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryClaimItemRepository;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryClaimRepository;
import com.cartmate.cartmate.global.event.DomainEvent;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import com.cartmate.cartmate.shared.delivery.event.DeliveryClaimReturnApprovedEvent;
import com.cartmate.cartmate.shared.member.SellerQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryClaimDecideUseCaseTest {

    private static final long CLAIM_ID = 10L;
    private static final long DELIVERY_ID = 50L;
    private static final long ORDER_ID = 100L;
    private static final long BUYER_ID = 3L;
    private static final long SELLER_MEMBER_ID = 7L;
    private static final long SELLER_ID = 70L;
    private static final long APPLE = 11L;
    private static final long PEAR = 12L;

    private final DeliveryClaimRepository claimRepository = mock(DeliveryClaimRepository.class);
    private final DeliveryClaimItemRepository claimItemRepository = mock(DeliveryClaimItemRepository.class);
    private final SellerQuery sellerQuery = mock(SellerQuery.class);
    private final DomainEventPublisher eventPublisher = mock(DomainEventPublisher.class);
    private final DeliveryClaimDecideUseCase useCase =
            new DeliveryClaimDecideUseCase(claimRepository, claimItemRepository, sellerQuery, eventPublisher);

    private DeliveryClaim claim;
    private List<DeliveryClaimItem> claimItems;

    @BeforeEach
    void setUp() {
        when(sellerQuery.isSellerOwner(SELLER_MEMBER_ID, SELLER_ID)).thenReturn(true);
    }

    // 사과(배송상품 11, 주문 항목 601)·배(12, 602)가 담긴 접수 상태의 클레임. 구매자 사정 반품은 검사를 통과한 상태로 만든다.
    private void claimOf(ClaimType type, ClaimReason reason) {
        Delivery delivery = mock(Delivery.class);
        when(delivery.getId()).thenReturn(DELIVERY_ID);
        when(delivery.getOrderId()).thenReturn(ORDER_ID);
        when(delivery.getSellerId()).thenReturn(SELLER_ID);
        when(delivery.getPurchaseType()).thenReturn(PurchaseType.INDIVIDUAL_GROUP);
        claim = DeliveryClaim.create(delivery, BUYER_ID, type, reason, null);
        claim.accept();
        ReflectionTestUtils.setField(claim, "id", CLAIM_ID);
        claimItems = List.of(claimItem(APPLE, 601L, 2L), claimItem(PEAR, 602L, 1L));
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim));
        when(claimItemRepository.findAllWithDeliveryItemByClaimIds(anyList())).thenReturn(claimItems);
    }

    private DeliveryClaimItem claimItem(long deliveryItemId, long orderItemId, long quantity) {
        DeliveryItem item = DeliveryItem.create(mock(Delivery.class), orderItemId, "상품", 1000L, quantity);
        ReflectionTestUtils.setField(item, "id", deliveryItemId);
        return DeliveryClaimItem.create(claim, item, quantity);
    }

    private static DeliveryClaimDecideRequest.Item approve(long deliveryItemId) {
        return new DeliveryClaimDecideRequest.Item(deliveryItemId, ClaimItemResult.APPROVED, null);
    }

    private static DeliveryClaimDecideRequest.Item reject(long deliveryItemId, String reason) {
        return new DeliveryClaimDecideRequest.Item(deliveryItemId, ClaimItemResult.REJECTED, reason);
    }

    private SellerClaimResponse decide(DeliveryClaimDecideRequest.Item... decisions) {
        return useCase.decide(CLAIM_ID, SELLER_MEMBER_ID, List.of(decisions));
    }

    @Test
    @DisplayName("일부만 승인하면 승인이 되고, 승인된 상품만 반품 승인 이벤트에 담고 일부 승인으로 표시한다")
    void decide_partial_publishesApprovedItemsOnly() {
        claimOf(ClaimType.REFUND, ClaimReason.PRODUCT_DEFECT);

        SellerClaimResponse response = decide(approve(APPLE), reject(PEAR, "사용 흔적이 있어 반품 불가"));

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(claim.getRefundStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(response.partiallyApproved()).isTrue();
        assertThat(response.items()).extracting("result")
                .containsExactly(ClaimItemResult.APPROVED, ClaimItemResult.REJECTED);
        assertThat(response.items().get(1).rejectReason()).isEqualTo("사용 흔적이 있어 반품 불가");

        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(captor.capture());
        DeliveryClaimReturnApprovedEvent event = (DeliveryClaimReturnApprovedEvent) captor.getValue();
        assertThat(event.claimId()).isEqualTo(CLAIM_ID);
        assertThat(event.orderId()).isEqualTo(ORDER_ID);
        assertThat(event.buyerMemberId()).isEqualTo(BUYER_ID);
        assertThat(event.sellerId()).isEqualTo(SELLER_ID);
        assertThat(event.sellerFault()).isTrue();
        assertThat(event.orderItemIds()).containsExactly(601L);
    }

    @Test
    @DisplayName("구매자 사정 반품의 승인 이벤트는 판매자 탓이 아니라고 알린다")
    void decide_buyerReason_eventIsNotSellerFault() {
        claimOf(ClaimType.REFUND, ClaimReason.SIMPLE_CHANGE);

        decide(approve(APPLE), approve(PEAR));

        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(captor.capture());
        DeliveryClaimReturnApprovedEvent event = (DeliveryClaimReturnApprovedEvent) captor.getValue();
        assertThat(event.sellerFault()).isFalse();
        assertThat(event.orderItemIds()).containsExactly(601L, 602L);
    }

    @Test
    @DisplayName("모든 상품을 거절하면 거절이 되고 환불 이벤트를 내지 않는다")
    void decide_allRejected_noEvent() {
        claimOf(ClaimType.REFUND, ClaimReason.PRODUCT_DEFECT);

        SellerClaimResponse response = decide(reject(APPLE, "기간 초과"), reject(PEAR, "기간 초과"));

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(claim.getRefundStatus()).isEqualTo(RefundStatus.NONE);
        assertThat(response.partiallyApproved()).isFalse();
        verify(eventPublisher, never()).publish(any(DomainEvent.class));
    }

    @Test
    @DisplayName("교환을 승인하면 환불 이벤트 없이 환불 불필요로 남는다")
    void decide_exchangeApproved_noEvent() {
        claimOf(ClaimType.EXCHANGE, ClaimReason.PRODUCT_DEFECT);

        decide(approve(APPLE), approve(PEAR));

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(claim.getRefundStatus()).isEqualTo(RefundStatus.NONE);
        verify(eventPublisher, never()).publish(any(DomainEvent.class));
    }

    @Test
    @DisplayName("클레임의 모든 상품에 한 번씩 결정하지 않으면 거절한다 (빠짐, 다른 상품, 같은 상품 두 번)")
    void decide_itemsMismatch_throws() {
        claimOf(ClaimType.REFUND, ClaimReason.PRODUCT_DEFECT);

        assertThatThrownBy(() -> decide(approve(APPLE))).hasMessageContaining("DELIVERY_CLAIM_DECISION_ITEMS_MISMATCH");
        assertThatThrownBy(() -> decide(approve(APPLE), approve(99L))).hasMessageContaining("DELIVERY_CLAIM_DECISION_ITEMS_MISMATCH");
        assertThatThrownBy(() -> decide(approve(APPLE), approve(APPLE))).hasMessageContaining("DELIVERY_CLAIM_DECISION_ITEMS_MISMATCH");
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REQUESTED);
    }

    @Test
    @DisplayName("거절한 상품에는 사유가 있어야 하고 너무 길 수 없다")
    void decide_rejectReasonRules() {
        claimOf(ClaimType.REFUND, ClaimReason.PRODUCT_DEFECT);

        assertThatThrownBy(() -> decide(approve(APPLE), reject(PEAR, " ")))
                .hasMessageContaining("DELIVERY_CLAIM_REJECT_REASON_REQUIRED");
        assertThatThrownBy(() -> decide(approve(APPLE), reject(PEAR, "가".repeat(DeliveryClaim.DETAIL_MAX_LENGTH + 1))))
                .hasMessageContaining("DELIVERY_CLAIM_REJECT_REASON_TOO_LONG");
    }

    @Test
    @DisplayName("확인 중인 클레임은 판매자가 결정할 수 없다")
    void decide_verifying_throws() {
        claimOf(ClaimType.REFUND, ClaimReason.SIMPLE_CHANGE);
        ReflectionTestUtils.setField(claim, "status", ClaimStatus.VERIFYING);

        assertThatThrownBy(() -> decide(approve(APPLE), approve(PEAR)))
                .hasMessageContaining("DELIVERY_CLAIM_INVALID_STATUS");
    }

    @Test
    @DisplayName("같은 결정을 다시 보내면 이벤트를 다시 내지 않고 현재 결과를 돌려준다")
    void decide_sameDecisionAgain_returnsWithoutEvent() {
        claimOf(ClaimType.REFUND, ClaimReason.PRODUCT_DEFECT);
        decide(approve(APPLE), reject(PEAR, "기간 초과"));

        SellerClaimResponse again = decide(approve(APPLE), reject(PEAR, "기간 초과"));

        assertThat(again.status()).isEqualTo(ClaimStatus.APPROVED);
        verify(eventPublisher, times(1)).publish(any(DomainEvent.class));
    }

    @Test
    @DisplayName("이미 처리된 클레임에 다른 결정을 보내면 상태 오류다")
    void decide_differentDecisionAgain_throws() {
        claimOf(ClaimType.REFUND, ClaimReason.PRODUCT_DEFECT);
        decide(approve(APPLE), reject(PEAR, "기간 초과"));

        assertThatThrownBy(() -> decide(approve(APPLE), approve(PEAR)))
                .hasMessageContaining("DELIVERY_CLAIM_INVALID_STATUS");
    }

    @Test
    @DisplayName("해당 배송의 판매자가 아니면 결정할 수 없다")
    void decide_notSeller_throwsAccessDenied() {
        claimOf(ClaimType.REFUND, ClaimReason.PRODUCT_DEFECT);

        assertThatThrownBy(() -> useCase.decide(CLAIM_ID, 999L, List.of(approve(APPLE), approve(PEAR))))
                .hasMessageContaining("DELIVERY_ACCESS_DENIED");
        verify(eventPublisher, never()).publish(any(DomainEvent.class));
    }
}
