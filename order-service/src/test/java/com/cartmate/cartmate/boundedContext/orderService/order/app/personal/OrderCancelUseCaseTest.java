package com.cartmate.cartmate.boundedContext.orderService.order.app.personal;

import com.cartmate.cartmate.shared.order.event.OrderSellerItemsCanceledEvent;
import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import com.cartmate.cartmate.global.http.InternalApiClient.InternalServerUnavailableException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cartmate.cartmate.boundedContext.orderService.order.exception.OrderErrorCode;

import com.cartmate.cartmate.global.exception.ServiceException;

import com.cartmate.cartmate.shared.payment.dto.PaymentResult;

import com.cartmate.cartmate.shared.payment.dto.PaymentOrderType;

import com.cartmate.cartmate.boundedContext.orderService.order.out.OrderPaymentClient;

import com.cartmate.cartmate.shared.order.OrderCancelReason;

import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.Order;
import com.cartmate.cartmate.shared.order.OrderStatus;
import com.cartmate.cartmate.boundedContext.orderService.order.out.personal.OrderRepository;
import com.cartmate.cartmate.shared.order.event.OrderCanceledEvent;
import com.cartmate.cartmate.global.event.DomainEvent;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.shared.order.event.OrderCouponRestoreRequestedEvent;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.itemcancel.OrderItemCancel;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.OrderItem;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.OrderCouponRule;
import com.cartmate.cartmate.boundedContext.orderService.order.out.itemcancel.OrderItemCancelRepository;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static com.cartmate.cartmate.boundedContext.orderService.order.support.OrderFixtures.ballLine;
import static com.cartmate.cartmate.boundedContext.orderService.order.support.OrderFixtures.personalOrder;
import static com.cartmate.cartmate.boundedContext.orderService.order.support.OrderFixtures.productBLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderCancelUseCaseTest {

    private static final Long ORDER_ID = 100L;
    private static final Long BUYER = 1L;
    private static final Clock CLOCK = Clock.fixed(
            LocalDateTime.of(2026, 10, 19, 12, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private DomainEventPublisher eventPublisher;

    @Mock
    private OrderPaymentClient paymentClient;

    @Mock
    private OrderItemCancelRepository cancelRepository;

    private OrderCancelUseCase cancelUseCase;

    @BeforeEach
    void setUp() {
        cancelUseCase = new OrderCancelUseCase(orderRepository, new OrderSupport(orderRepository), eventPublisher,
                paymentClient, cancelRepository, CLOCK);
    }

    @Test
    @DisplayName("결제 기한 전에는 기한 만료 취소를 하지 않고 이벤트도 발행하지 않는다")
    void cancelByDeadlineBeforeDeadline() {
        Order order = personalOrder(BUYER, ballLine(null, 1), LocalDateTime.now(CLOCK));
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));

        cancelUseCase.cancelByDeadline(ORDER_ID);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        then(eventPublisher).should(never()).publish(any(DomainEvent.class));
    }

    @Test
    @DisplayName("결제 대기 주문을 취소할 때 차감됐지만 반영 안 된 결제가 있으면 되돌린 뒤 취소한다 (ISS-27)")
    void releaseUnappliedChargeOnCancel() {
        Order order = order();
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", ORDER_ID);
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));
        given(paymentClient.findPayment(PaymentOrderType.INDIVIDUAL_GROUP, ORDER_ID, BUYER))
                .willReturn(Optional.of(paid())).willReturn(Optional.empty());

        cancelUseCase.cancelByBuyer(BUYER, ORDER_ID);

        then(paymentClient).should().compensateCharge(PaymentOrderType.INDIVIDUAL_GROUP, ORDER_ID, BUYER);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        ArgumentCaptor<OrderCanceledEvent> captor = ArgumentCaptor.forClass(OrderCanceledEvent.class);
        then(eventPublisher).should().publish(captor.capture());
        assertThat(captor.getValue().paid()).isFalse();
    }

    @Test
    @DisplayName("되돌린 뒤에도 결제가 남아 있으면 취소를 롤백하도록 예외를 던지고 이벤트를 내지 않는다 (돈이 빠진 채 취소 금지)")
    void rejectCancelWhenChargeStillPaid() {
        Order order = order();
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", ORDER_ID);
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));
        given(paymentClient.findPayment(PaymentOrderType.INDIVIDUAL_GROUP, ORDER_ID, BUYER)).willReturn(Optional.of(paid()));

        assertThatThrownBy(() -> cancelUseCase.cancelByBuyer(BUYER, ORDER_ID))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(OrderErrorCode.ORDER_PAYMENT_RELEASE_PENDING.getMsg());
        then(eventPublisher).should(never()).publish(any(DomainEvent.class));
    }

    @Test
    @DisplayName("결제 서버를 조회할 수 없으면(503) 취소를 진행하지 않고 그 예외를 그대로 던진다 — 돈이 빠졌는지 모른 채 취소 금지")
    void propagateOutageOnCancel() {
        Order order = order();
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", ORDER_ID);
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));
        given(paymentClient.findPayment(PaymentOrderType.INDIVIDUAL_GROUP, ORDER_ID, BUYER))
                .willThrow(new InternalServerUnavailableException("PAYMENT", "결제", null));

        assertThatThrownBy(() -> cancelUseCase.cancelByBuyer(BUYER, ORDER_ID))
                .isInstanceOf(InternalServerUnavailableException.class);
        then(eventPublisher).should(never()).publish(any(DomainEvent.class));
        then(paymentClient).should(never()).compensateCharge(any(), any(), any());
    }

    private PaymentResult paid() {
        return new PaymentResult(1L, ORDER_ID, 25000L, 0L, 25000L, "INDIVIDUAL_GROUP", "PAID",
                LocalDateTime.now(CLOCK), null);
    }

    private Order order() {
        return personalOrder(BUYER, ballLine(null, 1), LocalDateTime.now(CLOCK));
    }

    @Test
    @DisplayName("결제 전 주문의 취소 이벤트에는 환불 금액이 없다")
    void unpaidCancelHasNoRefundAmount() {
        Order order = order();
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", ORDER_ID);
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));
        given(paymentClient.findPayment(PaymentOrderType.INDIVIDUAL_GROUP, ORDER_ID, BUYER)).willReturn(Optional.empty());

        cancelUseCase.cancelByBuyer(BUYER, ORDER_ID);

        ArgumentCaptor<OrderCanceledEvent> captor = ArgumentCaptor.forClass(OrderCanceledEvent.class);
        then(eventPublisher).should().publish(captor.capture());
        assertThat(captor.getValue().refundAmount()).isNull();
    }

    @Test
    @DisplayName("판매자 사유로 한 판매자 몫을 취소하면 환불 금액, 사유, 취소 건 ID를 실은 판매자 몫 취소 이벤트를 발행한다")
    void cancelItemsPublishesReasonAndCancelId() {
        Order order = personalOrder("20261019-TWOSELLR", BUYER,
                List.of(ballLine(10L, 1), productBLine(3L, 1)), LocalDateTime.now(CLOCK));
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", ORDER_ID);
        order.markPaid(1L, LocalDateTime.now(CLOCK));
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));
        givenCancelIdsFromOne();

        Optional<Long> cancelId = cancelUseCase.cancelItems(ORDER_ID, 7L, null,
                DeliveryCancelReason.SELLER_OUT_OF_STOCK);

        assertThat(cancelId).contains(1L);
        then(eventPublisher).should().publish(new OrderSellerItemsCanceledEvent(
                ORDER_ID, BUYER, 7L, 15_000L, LocalDateTime.now(CLOCK), DeliveryCancelReason.SELLER_OUT_OF_STOCK, 1L));
    }

    @Test
    @DisplayName("같은 판매자의 상품을 두 번 나눠 취소하면 취소 건이 두 개 생기고, 환불 이벤트마다 자기 취소 건 ID를 싣는다")
    void cancelItems_twiceForSameSeller_createsTwoBatchesWithOwnIds() {
        Order order = appleBananaMilkOrder();
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));
        givenCancelIdsFromOne();
        LocalDateTime now = LocalDateTime.now(CLOCK);

        Optional<Long> first = cancelUseCase.cancelItems(ORDER_ID, 7L, List.of(APPLE),
                DeliveryCancelReason.SELLER_OUT_OF_STOCK);
        Optional<Long> second = cancelUseCase.cancelItems(ORDER_ID, 7L, List.of(PEAR),
                DeliveryCancelReason.SELLER_OUT_OF_STOCK);

        assertThat(first).contains(1L);
        assertThat(second).contains(2L);
        then(eventPublisher).should().publish(new OrderSellerItemsCanceledEvent(
                ORDER_ID, BUYER, 7L, 10_000L, now, DeliveryCancelReason.SELLER_OUT_OF_STOCK, 1L));
        then(eventPublisher).should().publish(new OrderSellerItemsCanceledEvent(
                ORDER_ID, BUYER, 7L, 5_000L, now, DeliveryCancelReason.SELLER_OUT_OF_STOCK, 2L));
        assertThat(order.getItems()).extracting(OrderItem::getCancelId).containsExactly(1L, 2L, null);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("이미 취소된 항목을 다시 취소하면 취소 건도 이벤트도 만들지 않는다")
    void cancelItems_sameItemsAgain_publishesNothing() {
        Order order = appleBananaMilkOrder();
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));
        givenCancelIdsFromOne();
        cancelUseCase.cancelItems(ORDER_ID, 7L, List.of(APPLE),
                DeliveryCancelReason.SELLER_OUT_OF_STOCK);

        Optional<Long> again = cancelUseCase.cancelItems(ORDER_ID, 7L, List.of(APPLE),
                DeliveryCancelReason.SELLER_OUT_OF_STOCK);

        assertThat(again).isEmpty();
        then(cancelRepository).should(org.mockito.Mockito.times(1)).save(any());
        then(eventPublisher).should(org.mockito.Mockito.times(1)).publish(any());
    }

    @Test
    @DisplayName("결제 대기 주문은 고른 항목과 상관없이 바로 주문 전체를 취소하고 취소 이벤트를 낸다")
    void startBuyerCancel_paymentPending_cancelsWholeOrder() {
        Order order = order();
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", ORDER_ID);
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));
        given(paymentClient.findPayment(PaymentOrderType.INDIVIDUAL_GROUP, ORDER_ID, BUYER)).willReturn(Optional.empty());

        BuyerCancelStart start = cancelUseCase.startBuyerCancel(BUYER, ORDER_ID, null);

        assertThat(start.canceledNow()).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(order.getCancelReason()).isEqualTo(OrderCancelReason.BUYER_CANCELED);
        then(eventPublisher).should().publish(any(OrderCanceledEvent.class));
    }

    @Test
    @DisplayName("결제 완료 주문은 주문을 바꾸지 않고, 고른 항목과 그 판매자를 배송 서버에 물어볼 목록으로 돌려준다")
    void startBuyerCancel_paid_returnsTargetsWithoutChangingOrder() {
        Order order = appleBananaMilkOrder();
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));

        BuyerCancelStart start = cancelUseCase.startBuyerCancel(BUYER, ORDER_ID, List.of(PEAR, MILK));

        assertThat(start.canceledNow()).isFalse();
        assertThat(start.targets()).containsExactly(
                new com.cartmate.cartmate.boundedContext.orderService.order.domain.buyercancel.BuyerCancelRequest.Target(PEAR, 7L),
                new com.cartmate.cartmate.boundedContext.orderService.order.domain.buyercancel.BuyerCancelRequest.Target(MILK, 8L));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getItems()).noneMatch(OrderItem::isCanceled);
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("판매자 사유 일부 취소 뒤 구매자가 나머지를 취소하면 쿠폰 복구를 한 번 요청하고, 같은 취소가 다시 와도 또 요청하지 않는다")
    void cancelAllInSteps_returnsCouponOnce() {
        Order order = couponAppleBananaMilkOrder(OrderCouponRule.of("FIXED", 1_000L, null, 0L));
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));
        givenCancelIdsFromOne();
        OrderCouponRestoreRequestedEvent restore = new OrderCouponRestoreRequestedEvent(ORDER_ID, BUYER, 5L, "ORDER");

        cancelUseCase.cancelItems(ORDER_ID, 7L, null, DeliveryCancelReason.SELLER_OUT_OF_STOCK);
        then(eventPublisher).should(never()).publish(restore);
        cancelUseCase.cancelItems(ORDER_ID, 8L, List.of(MILK), DeliveryCancelReason.BUYER_CANCELED);
        cancelUseCase.cancelItems(ORDER_ID, 8L, List.of(MILK), DeliveryCancelReason.BUYER_CANCELED);

        then(eventPublisher).should(org.mockito.Mockito.times(1)).publish(restore);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(order.isCouponReturned()).isTrue();
    }

    @Test
    @DisplayName("구매자 일부 취소는 결제 때 고정한 할인만 빼고 돌려준다 — 쿠폰 최소 금액이 깨져도 거절하지 않는다 (B 1,000원 - 할인 273원)")
    void cancelItems_buyerCancel_refundsAmountMinusFixedDiscount() {
        Order order = personalOrder("20261019-FIXEDDIS", BUYER, List.of(
                new Order.OrderLine(10L, 11L, 7L, "상품A", null, 10_000L, 1),
                new Order.OrderLine(30L, 31L, 8L, "상품B", null, 1_000L, 1)), LocalDateTime.now(CLOCK));
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", ORDER_ID);
        org.springframework.test.util.ReflectionTestUtils.setField(order.getItems().get(0), "id", APPLE);
        org.springframework.test.util.ReflectionTestUtils.setField(order.getItems().get(1), "id", PEAR);
        order.applyCoupon(5L, 3_000L, true, null, OrderCouponRule.of("FIXED", 3_000L, null, 11_000L));
        order.markPaid(1L, LocalDateTime.now(CLOCK));
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));
        givenCancelIdsFromOne();

        assertThat(cancelUseCase.startBuyerCancel(BUYER, ORDER_ID, List.of(PEAR)).canceledNow()).isFalse();
        cancelUseCase.cancelItems(ORDER_ID, 8L, List.of(PEAR), DeliveryCancelReason.BUYER_CANCELED);

        then(eventPublisher).should().publish(new OrderSellerItemsCanceledEvent(
                ORDER_ID, BUYER, 8L, 727L, LocalDateTime.now(CLOCK), DeliveryCancelReason.BUYER_CANCELED, 1L));
        then(eventPublisher).should(never()).publish(any(OrderCouponRestoreRequestedEvent.class));
    }

    @Test
    @DisplayName("결제 진행 중 쿠폰을 기록해 둔 결제 대기 주문이 기한 만료로 취소되면 쿠폰 복구를 요청한다 (쿠폰 사용 뒤 결제가 끝나지 못한 경우)")
    void cancelByDeadline_withRecordedCoupon_requestsRestore() {
        Order order = order();
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", ORDER_ID);
        order.recordPaymentCoupon(5L);
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));
        given(paymentClient.findPayment(PaymentOrderType.INDIVIDUAL_GROUP, ORDER_ID, BUYER)).willReturn(Optional.empty());
        org.springframework.test.util.ReflectionTestUtils.setField(order, "paymentDeadlineAt", LocalDateTime.now(CLOCK));

        cancelUseCase.cancelByDeadline(ORDER_ID);

        then(eventPublisher).should().publish(new OrderCouponRestoreRequestedEvent(ORDER_ID, BUYER, 5L, "ORDER"));
        assertThat(order.isCouponReturned()).isTrue();
    }

    @Test
    @DisplayName("구매자 취소 결과로 여러 판매자 상품을 취소하면 판매자마다 자기 취소 건 ID로 환불 이벤트를 하나씩 낸다")
    void cancelItemsByBuyer_publishesEventPerSellerWithOwnCancelId() {
        Order order = appleBananaMilkOrder();
        given(orderRepository.findByIdForUpdate(ORDER_ID)).willReturn(Optional.of(order));
        givenCancelIdsFromOne();
        java.util.Map<Long, List<Long>> bySeller = new java.util.LinkedHashMap<>();
        bySeller.put(7L, List.of(APPLE));
        bySeller.put(8L, List.of(MILK));

        List<Long> cancelIds = cancelUseCase.cancelItemsByBuyer(ORDER_ID, bySeller);

        LocalDateTime now = LocalDateTime.now(CLOCK);
        assertThat(cancelIds).containsExactly(1L, 2L);
        then(eventPublisher).should().publish(new OrderSellerItemsCanceledEvent(
                ORDER_ID, BUYER, 7L, 10_000L, now, DeliveryCancelReason.BUYER_CANCELED, 1L));
        then(eventPublisher).should().publish(new OrderSellerItemsCanceledEvent(
                ORDER_ID, BUYER, 8L, 3_000L, now, DeliveryCancelReason.BUYER_CANCELED, 2L));
        assertThat(order.getItems()).extracting(OrderItem::getCancelId).containsExactly(1L, null, 2L);
    }

    private Order couponAppleBananaMilkOrder(OrderCouponRule rule) {
        Order order = personalOrder("20261019-COUPONAB", BUYER, List.of(
                new Order.OrderLine(10L, 11L, 7L, "사과", null, 10_000L, 1),
                new Order.OrderLine(20L, 21L, 7L, "배", null, 5_000L, 1),
                new Order.OrderLine(30L, 31L, 8L, "우유", null, 3_000L, 1)), LocalDateTime.now(CLOCK));
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", ORDER_ID);
        org.springframework.test.util.ReflectionTestUtils.setField(order.getItems().get(0), "id", APPLE);
        org.springframework.test.util.ReflectionTestUtils.setField(order.getItems().get(1), "id", PEAR);
        org.springframework.test.util.ReflectionTestUtils.setField(order.getItems().get(2), "id", MILK);
        order.applyCoupon(5L, 1_000L, true, null, rule);
        order.markPaid(1L, LocalDateTime.now(CLOCK));
        return order;
    }

    private static final Long APPLE = 501L;
    private static final Long PEAR = 502L;
    private static final Long MILK = 503L;

    private Order appleBananaMilkOrder() {
        Order order = personalOrder("20261019-APPLEPEA", BUYER, List.of(
                new Order.OrderLine(10L, 11L, 7L, "사과", null, 10_000L, 1),
                new Order.OrderLine(20L, 21L, 7L, "배", null, 5_000L, 1),
                new Order.OrderLine(30L, 31L, 8L, "우유", null, 3_000L, 1)), LocalDateTime.now(CLOCK));
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", ORDER_ID);
        org.springframework.test.util.ReflectionTestUtils.setField(order.getItems().get(0), "id", APPLE);
        org.springframework.test.util.ReflectionTestUtils.setField(order.getItems().get(1), "id", PEAR);
        org.springframework.test.util.ReflectionTestUtils.setField(order.getItems().get(2), "id", MILK);
        order.markPaid(1L, LocalDateTime.now(CLOCK));
        return order;
    }

    private void givenCancelIdsFromOne() {
        AtomicLong seq = new AtomicLong();
        given(cancelRepository.save(any(OrderItemCancel.class))).willAnswer(invocation -> {
            OrderItemCancel cancel = invocation.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(cancel, "id", seq.incrementAndGet());
            return cancel;
        });
    }
}
