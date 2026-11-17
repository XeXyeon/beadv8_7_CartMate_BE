package com.cartmate.cartmate.boundedContext.etcService.delivery.app;

import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.DeliveryClaimCreateCommand;
import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.DeliveryClaimResponse;
import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.DeliveryOrderRef;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimItemResult;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimReason;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimStatus;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimType;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.Delivery;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryClaim;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryClaimItem;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryItem;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryClaimItemRepository;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryClaimRepository;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryItemRepository;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryRepository;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import com.cartmate.cartmate.shared.delivery.event.DeliveryClaimReturnRequestedEvent;
import com.cartmate.cartmate.shared.delivery.event.PendingReturn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
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
    private final DomainEventPublisher eventPublisher = mock(DomainEventPublisher.class);
    private final DeliveryClaimCreateUseCase useCase = new DeliveryClaimCreateUseCase(
            deliveryRepository, deliveryItemRepository, claimRepository, claimItemRepository, eventPublisher);

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
                List.of(new DeliveryClaimCreateCommand.Item(APPLE_ID)))))
                .hasMessageContaining("DELIVERY_CLAIM_ITEM_CANCELED");
        verify(claimRepository, never()).save(any());
    }

    // 사과(배송 수량 3, 주문 항목 601)가 든 배송완료 배송. returned가 true면 사과 반품이 이미 승인된 상태.
    private void deliveredApple(boolean returned) {
        Delivery delivery = Delivery.create(MEMBER_ID, 1007L, 100L, "O-100", PurchaseType.INDIVIDUAL_GROUP,
                "수령인", "010", "00000", "서울", "101호", "key-1", "hash");
        DeliveryItem apple = DeliveryItem.create(delivery, 601L, "사과", 1000L, 3L);
        ReflectionTestUtils.setField(apple, "id", APPLE_ID);
        delivery.prepare("CJ", "123");
        delivery.ship();
        delivery.deliver();
        when(deliveryRepository.findByIdForClaim(DELIVERY_ID)).thenReturn(Optional.of(delivery));
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));
        when(deliveryItemRepository.findByDeliveryId(DELIVERY_ID)).thenReturn(List.of(apple));
        when(claimItemRepository.findApprovedReturnedDeliveryItemIds(
                eq(DELIVERY_ID), eq(ClaimType.REFUND), anyCollection(), eq(ClaimItemResult.APPROVED)))
                .thenReturn(returned ? List.of(APPLE_ID) : List.of());
    }

    private DeliveryClaimCreateCommand apple(ClaimType type, ClaimReason reason) {
        return new DeliveryClaimCreateCommand(DELIVERY_ID, MEMBER_ID, type, reason, null,
                List.of(new DeliveryClaimCreateCommand.Item(APPLE_ID)));
    }

    @Test
    @DisplayName("이미 승인된 반품이 있는 상품은 다시 반품할 수 없다")
    void create_returnedItemRefund_throwsAlreadyReturned() {
        deliveredApple(true);

        assertThatThrownBy(() -> useCase.create(apple(ClaimType.REFUND, ClaimReason.PRODUCT_DEFECT)))
                .hasMessageContaining("DELIVERY_CLAIM_ITEM_ALREADY_RETURNED");
        verify(claimRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미 반품된 상품은 교환도 할 수 없다")
    void create_returnedItemExchange_throwsAlreadyReturned() {
        deliveredApple(true);

        assertThatThrownBy(() -> useCase.create(apple(ClaimType.EXCHANGE, ClaimReason.PRODUCT_DEFECT)))
                .hasMessageContaining("DELIVERY_CLAIM_ITEM_ALREADY_RETURNED");
    }

    @Test
    @DisplayName("반품된 적 없는 상품은 접수되고, 상품을 고르면 배송 상품의 수량 전부가 담긴다 (사과 3개)")
    void create_notReturnedItem_usesWholeDeliveryQuantity() {
        deliveredApple(false);

        DeliveryClaimResponse response = useCase.create(apple(ClaimType.REFUND, ClaimReason.PRODUCT_DEFECT));

        assertThat(response.status()).isEqualTo(ClaimStatus.REQUESTED);
        assertThat(response.items()).extracting(DeliveryClaimResponse.Item::quantity).containsExactly(3L);
    }

    @Test
    @DisplayName("구매자 사정 반품은 확인 중으로 접수하고 주문 서버에 접수 검사를 요청한다")
    void create_buyerReasonRefund_publishesReturnRequested() {
        deliveredApple(false);

        assertThat(useCase.create(apple(ClaimType.REFUND, ClaimReason.SIMPLE_CHANGE)).status())
                .isEqualTo(ClaimStatus.VERIFYING);

        ArgumentCaptor<DeliveryClaimReturnRequestedEvent> captor =
                ArgumentCaptor.forClass(DeliveryClaimReturnRequestedEvent.class);
        verify(eventPublisher).publish(captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo(100L);
        assertThat(captor.getValue().buyerMemberId()).isEqualTo(MEMBER_ID);
        assertThat(captor.getValue().orderItemIds()).containsExactly(601L);
        assertThat(captor.getValue().pendingReturns()).isEmpty();
    }

    @Test
    @DisplayName("판매자 탓 반품과 교환은 검사 없이 바로 접수하고 검사 요청을 보내지 않는다")
    void create_sellerFaultRefundAndExchange_noReturnRequest() {
        deliveredApple(false);

        assertThat(useCase.create(apple(ClaimType.REFUND, ClaimReason.PRODUCT_DEFECT)).status())
                .isEqualTo(ClaimStatus.REQUESTED);
        assertThat(useCase.create(apple(ClaimType.EXCHANGE, ClaimReason.SIMPLE_CHANGE)).status())
                .isEqualTo(ClaimStatus.REQUESTED);

        verify(eventPublisher, never()).publish(any());
        verify(claimItemRepository, never()).findPendingReturnItems(any(), any(), any(), anyCollection(), any());
    }

    @Test
    @DisplayName("같은 주문에서 처리 중인 다른 반품이 있으면 접수 검사 요청에 그 상품과 판매자 탓 여부를 함께 싣는다")
    void create_buyerReasonRefund_carriesPendingReturnsOfSameOrder() {
        deliveredApple(false);
        DeliveryClaim other = DeliveryClaim.create(mock(Delivery.class), 3L, ClaimType.REFUND, ClaimReason.PRODUCT_DEFECT, null);
        ReflectionTestUtils.setField(other, "id", 77L);
        DeliveryItem pear = DeliveryItem.create(mock(Delivery.class), 602L, "배", 2000L, 1L);
        ReflectionTestUtils.setField(pear, "id", PEAR_ID);
        when(claimItemRepository.findPendingReturnItems(eq(100L), eq(PurchaseType.INDIVIDUAL_GROUP), eq(ClaimType.REFUND),
                anyCollection(), eq(ClaimItemResult.APPROVED)))
                .thenReturn(List.of(DeliveryClaimItem.create(other, pear, 1L)));

        useCase.create(apple(ClaimType.REFUND, ClaimReason.SIMPLE_CHANGE));

        ArgumentCaptor<DeliveryClaimReturnRequestedEvent> captor =
                ArgumentCaptor.forClass(DeliveryClaimReturnRequestedEvent.class);
        verify(eventPublisher).publish(captor.capture());
        assertThat(captor.getValue().pendingReturns()).containsExactly(new PendingReturn(true, List.of(602L)));
    }

    @Test
    @DisplayName("접수 락을 잡으려고 배송의 주문과 구매 유형을 조회한다")
    void orderRefOf_returnsOrderAndPurchaseType() {
        deliveredApple(false);

        assertThat(useCase.orderRefOf(DELIVERY_ID))
                .isEqualTo(new DeliveryOrderRef(100L, PurchaseType.INDIVIDUAL_GROUP));
    }

    @Test
    @DisplayName("없는 배송이면 접수와 같은 오류로 거절한다")
    void orderRefOf_unknownDelivery_throwsNotFound() {
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.orderRefOf(DELIVERY_ID)).hasMessageContaining("DELIVERY_NOT_FOUND");
    }

    @Test
    @DisplayName("확인 중인 클레임에 담긴 상품은 다시 접수할 수 없다")
    void create_itemInVerifyingClaim_throwsAlreadyExists() {
        deliveredApple(false);
        when(claimItemRepository.existsByDeliveryItemIdsAndClaimStatuses(
                any(), eq(ClaimStatus.inProgress()))).thenReturn(true);

        assertThatThrownBy(() -> useCase.create(apple(ClaimType.REFUND, ClaimReason.SIMPLE_CHANGE)))
                .hasMessageContaining("DELIVERY_CLAIM_ALREADY_EXISTS");
    }

    private DeliveryItem item(Delivery delivery, long id, long orderItemId) {
        DeliveryItem item = DeliveryItem.create(delivery, orderItemId, "상품", 1000L, 1L);
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }
}
