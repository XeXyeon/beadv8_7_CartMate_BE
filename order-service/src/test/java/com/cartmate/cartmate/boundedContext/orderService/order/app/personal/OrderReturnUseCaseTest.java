package com.cartmate.cartmate.boundedContext.orderService.order.app.personal;

import com.cartmate.cartmate.boundedContext.orderService.order.domain.claimreturn.OrderItemReturn;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.Order;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.Order.OrderLine;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.OrderCouponRule;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.OrderReturnPreview;
import com.cartmate.cartmate.boundedContext.orderService.order.exception.OrderErrorCode;
import com.cartmate.cartmate.boundedContext.orderService.order.out.OrderCouponClient;
import com.cartmate.cartmate.boundedContext.orderService.order.out.claimreturn.OrderItemReturnRepository;
import com.cartmate.cartmate.boundedContext.orderService.order.out.personal.OrderRepository;
import com.cartmate.cartmate.global.event.DomainEvent;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import com.cartmate.cartmate.shared.delivery.event.DeliveryClaimReturnApprovedEvent;
import com.cartmate.cartmate.shared.delivery.event.DeliveryClaimReturnRequestedEvent;
import com.cartmate.cartmate.shared.delivery.event.PendingReturn;
import com.cartmate.cartmate.shared.order.event.OrderClaimRefundRequestedEvent;
import com.cartmate.cartmate.shared.order.event.OrderClaimReturnVerifiedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static com.cartmate.cartmate.boundedContext.orderService.order.support.OrderFixtures.addressWithoutDetail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class OrderReturnUseCaseTest {

    private static final Long ORDER_ID = 100L;
    private static final Long CLAIM_ID = 900L;
    private static final Long BUYER = 1L;
    private static final Long ITEM_A = 701L;
    private static final Long ITEM_B = 702L;
    private static final Long ITEM_C = 703L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 17, 12, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private DomainEventPublisher eventPublisher;
    @Mock
    private OrderCouponClient couponClient;
    @Mock
    private OrderItemReturnRepository returnRepository;

    private OrderReturnUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new OrderReturnUseCase(orderRepository, new OrderSupport(orderRepository), eventPublisher,
                couponClient, returnRepository, CLOCK);
    }

    // A 10,000원·B 1,000원·C 500원(판매자 7·8·9), "11,000원 이상 3,000원 할인" 플랫폼 쿠폰, 결제 8,500원
    private Order paidCouponOrder() {
        Order order = Order.createPersonal("ORD-RU", BUYER, List.of(
                new OrderLine(10L, 11L, 7L, "A", null, 10_000L, 1),
                new OrderLine(20L, 21L, 8L, "B", null, 1_000L, 1),
                new OrderLine(30L, 31L, 9L, "C", null, 500L, 1)
        ), addressWithoutDetail(), NOW.minusDays(5));
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        ReflectionTestUtils.setField(order.getItems().get(0), "id", ITEM_A);
        ReflectionTestUtils.setField(order.getItems().get(1), "id", ITEM_B);
        ReflectionTestUtils.setField(order.getItems().get(2), "id", ITEM_C);
        order.applyCoupon(5L, 3_000L, true, null, OrderCouponRule.of("FIXED", 3_000L, null, 11_000L));
        order.markPaid(99L, NOW.minusDays(5).plusMinutes(1));
        return order;
    }

    private DeliveryClaimReturnRequestedEvent requested(Long... itemIds) {
        return new DeliveryClaimReturnRequestedEvent(CLAIM_ID, ORDER_ID, PurchaseType.INDIVIDUAL_GROUP, BUYER, List.of(itemIds), List.of());
    }

    // A 2,000원·B 2,000원·C 10,000원(판매자 7·8·9), "12,000원 이상 3,000원 할인" 플랫폼 쿠폰, 결제 11,000원
    private Order minimumOrder() {
        Order order = Order.createPersonal("ORD-RU4", BUYER, List.of(
                new OrderLine(10L, 11L, 7L, "A", null, 2_000L, 1),
                new OrderLine(20L, 21L, 8L, "B", null, 2_000L, 1),
                new OrderLine(30L, 31L, 9L, "C", null, 10_000L, 1)
        ), addressWithoutDetail(), NOW.minusDays(5));
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        ReflectionTestUtils.setField(order.getItems().get(0), "id", ITEM_A);
        ReflectionTestUtils.setField(order.getItems().get(1), "id", ITEM_B);
        ReflectionTestUtils.setField(order.getItems().get(2), "id", ITEM_C);
        order.applyCoupon(5L, 3_000L, true, null, OrderCouponRule.of("FIXED", 3_000L, null, 12_000L));
        order.markPaid(99L, NOW.minusDays(5).plusMinutes(1));
        return order;
    }

    private DeliveryClaimReturnApprovedEvent approved(boolean sellerFault, Long... itemIds) {
        return new DeliveryClaimReturnApprovedEvent(CLAIM_ID, 50L, ORDER_ID, PurchaseType.INDIVIDUAL_GROUP, BUYER, 8L,
                sellerFault, List.of(itemIds));
    }

    private <T extends DomainEvent> T publishedOnly(Class<T> type) {
        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        then(eventPublisher).should().publish(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(type);
        return type.cast(captor.getValue());
    }

    @Test
    @DisplayName("쿠폰 최소 금액이 깨져 환불이 음수가 되는 반품은 접수 검사에서 거절한다")
    void verify_underpaid_publishesRejected() {
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(paidCouponOrder()));

        useCase.verifyReturn(requested(ITEM_B));

        OrderClaimReturnVerifiedEvent event = publishedOnly(OrderClaimReturnVerifiedEvent.class);
        assertThat(event.claimId()).isEqualTo(CLAIM_ID);
        assertThat(event.accepted()).isFalse();
        assertThat(event.rejectCode()).isEqualTo(OrderErrorCode.ORDER_RETURN_COUPON_MINIMUM.resultCode());
    }

    @Test
    @DisplayName("같은 주문에서 처리 중인 다른 반품이 먼저 환불된 것으로 보고 접수 검사를 한다 (A 처리 중 → B는 거절)")
    void verify_pendingReturn_publishesRejected() {
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(minimumOrder()));
        DeliveryClaimReturnRequestedEvent event = new DeliveryClaimReturnRequestedEvent(CLAIM_ID, ORDER_ID,
                PurchaseType.INDIVIDUAL_GROUP, BUYER, List.of(ITEM_B), List.of(new PendingReturn(false, List.of(ITEM_A))));

        useCase.verifyReturn(event);

        OrderClaimReturnVerifiedEvent verified = publishedOnly(OrderClaimReturnVerifiedEvent.class);
        assertThat(verified.accepted()).isFalse();
        assertThat(verified.rejectCode()).isEqualTo(OrderErrorCode.ORDER_RETURN_COUPON_MINIMUM.resultCode());
    }

    @Test
    @DisplayName("처리 중인 다른 반품이 없으면 같은 B 반품도 접수 검사를 통과한다")
    void verify_noPendingReturn_publishesAccepted() {
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(minimumOrder()));

        useCase.verifyReturn(requested(ITEM_B));

        assertThat(publishedOnly(OrderClaimReturnVerifiedEvent.class).accepted()).isTrue();
    }

    @Test
    @DisplayName("주문을 찾을 수 없으면 예외로 올리지 않고 접수 불가로 답한다 (클레임이 확인 중에 멈추지 않게)")
    void verify_orderNotFound_publishesRejected() {
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.empty());

        useCase.verifyReturn(requested(ITEM_A));

        OrderClaimReturnVerifiedEvent verified = publishedOnly(OrderClaimReturnVerifiedEvent.class);
        assertThat(verified.accepted()).isFalse();
        assertThat(verified.rejectCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND.resultCode());
    }

    @Test
    @DisplayName("환불이 음수가 아니면 접수 검사를 통과한다")
    void verify_ok_publishesAccepted() {
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(paidCouponOrder()));

        useCase.verifyReturn(requested(ITEM_A, ITEM_B));

        OrderClaimReturnVerifiedEvent event = publishedOnly(OrderClaimReturnVerifiedEvent.class);
        assertThat(event.accepted()).isTrue();
        assertThat(event.rejectCode()).isNull();
    }

    @Test
    @DisplayName("결제 완료가 아닌 주문의 반품은 접수 검사에서 거절한다")
    void verify_notPaid_publishesRejected() {
        Order unpaid = Order.createPersonal("ORD-RU2", BUYER, List.of(
                new OrderLine(10L, 11L, 7L, "A", null, 10_000L, 1)), addressWithoutDetail(), NOW);
        ReflectionTestUtils.setField(unpaid.getItems().get(0), "id", ITEM_A);
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(unpaid));

        useCase.verifyReturn(requested(ITEM_A));

        assertThat(publishedOnly(OrderClaimReturnVerifiedEvent.class).rejectCode())
                .isEqualTo(OrderErrorCode.ORDER_RETURN_NOT_PAID.resultCode());
    }

    @Test
    @DisplayName("주문에 없는 항목의 반품은 접수 검사에서 거절한다")
    void verify_invalidItem_publishesRejected() {
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(paidCouponOrder()));

        useCase.verifyReturn(requested(999L));

        assertThat(publishedOnly(OrderClaimReturnVerifiedEvent.class).rejectCode())
                .isEqualTo(OrderErrorCode.ORDER_RETURN_ITEM_INVALID.resultCode());
    }

    @Test
    @DisplayName("구매자 사정 반품은 쿠폰을 다시 계산해 환불 금액을 정하고 결제 서버에 요청한다 (A·B 반품 → 8,000원)")
    void apply_buyerReason_recalculates() {
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(paidCouponOrder()));

        useCase.applyReturn(approved(false, ITEM_A, ITEM_B));

        OrderClaimRefundRequestedEvent event = publishedOnly(OrderClaimRefundRequestedEvent.class);
        assertThat(event.claimId()).isEqualTo(CLAIM_ID);
        assertThat(event.orderId()).isEqualTo(ORDER_ID);
        assertThat(event.buyerMemberId()).isEqualTo(BUYER);
        assertThat(event.amount()).isEqualTo(8_000L);
        ArgumentCaptor<OrderItemReturn> saved = ArgumentCaptor.forClass(OrderItemReturn.class);
        then(returnRepository).should().save(saved.capture());
        assertThat(saved.getValue().getClaimId()).isEqualTo(CLAIM_ID);
        assertThat(saved.getValue().getAmount()).isEqualTo(8_000L);
    }

    @Test
    @DisplayName("판매자 탓 반품은 할인을 비율로 나눈다 (B 반품 → 740원)")
    void apply_sellerFault_proportional() {
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(paidCouponOrder()));

        useCase.applyReturn(approved(true, ITEM_B));

        assertThat(publishedOnly(OrderClaimRefundRequestedEvent.class).amount()).isEqualTo(740L);
    }

    @Test
    @DisplayName("환불이 0원이어도 결과 이벤트를 받을 수 있게 환불 요청을 낸다")
    void apply_zeroAmount_stillPublishesRequest() {
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(paidCouponOrder()));

        useCase.applyReturn(approved(false, ITEM_B));

        assertThat(publishedOnly(OrderClaimRefundRequestedEvent.class).amount()).isZero();
    }

    @Test
    @DisplayName("같은 클레임의 승인 이벤트를 두 번 받아도 한 번만 반영한다")
    void apply_sameClaimTwice_recordsOnce() {
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(paidCouponOrder()));
        given(returnRepository.existsByClaimId(CLAIM_ID)).willReturn(false, true);

        useCase.applyReturn(approved(false, ITEM_A, ITEM_B));
        useCase.applyReturn(approved(false, ITEM_A, ITEM_B));

        then(returnRepository).should(times(1)).save(any(OrderItemReturn.class));
        then(eventPublisher).should(times(1)).publish(any(DomainEvent.class));
    }

    @Test
    @DisplayName("반품으로 쿠폰 혜택이 0이 되면 쿠폰을 돌려준다")
    void apply_couponReturnable_restoresCoupon() {
        Order order = Order.createPersonal("ORD-RU3", BUYER, List.of(
                new OrderLine(10L, 11L, 7L, "D", null, 6_000L, 2)), addressWithoutDetail(), NOW.minusDays(5));
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        ReflectionTestUtils.setField(order.getItems().get(0), "id", ITEM_A);
        order.applyCoupon(5L, 2_000L, true, null, OrderCouponRule.of("FIXED", 2_000L, null, 10_000L));
        order.markPaid(99L, NOW.minusDays(5).plusMinutes(1));
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));

        useCase.applyReturn(approved(false, ITEM_A));

        then(couponClient).should().restore(BUYER, 5L, ORDER_ID);
    }

    @Test
    @DisplayName("쿠폰을 돌려줄 일이 없으면 쿠폰 반환을 호출하지 않는다")
    void apply_couponStays_noRestore() {
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(paidCouponOrder()));

        useCase.applyReturn(approved(false, ITEM_B));

        then(couponClient).should(never()).restore(any(), any(), any());
    }

    @Test
    @DisplayName("구매자 예상 환불은 구매자 본인만 볼 수 있다")
    void previewBuyerReturn_notBuyer_throws() {
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(paidCouponOrder()));

        assertThatThrownBy(() -> useCase.previewBuyerReturn(99L, ORDER_ID, List.of(ITEM_A), false))
                .hasMessageContaining("ORDER_BUYER_ONLY");
    }

    @Test
    @DisplayName("구매자 사정 반품이 쿠폰 최소 금액을 깨면 예상 환불 조회가 거절한다")
    void previewBuyerReturn_underpaid_throws() {
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(paidCouponOrder()));

        assertThatThrownBy(() -> useCase.previewBuyerReturn(BUYER, ORDER_ID, List.of(ITEM_B), false))
                .hasMessageContaining("ORDER_RETURN_COUPON_MINIMUM");
    }

    @Test
    @DisplayName("판매자 탓 반품의 예상 환불은 할인을 비율로 나눠 계산한다")
    void previewBuyerReturn_sellerFault_proportional() {
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(paidCouponOrder()));

        OrderReturnPreview preview = useCase.previewBuyerReturn(BUYER, ORDER_ID, List.of(ITEM_B), true);

        assertThat(preview.refundAmount()).isEqualTo(740L);
    }
}
