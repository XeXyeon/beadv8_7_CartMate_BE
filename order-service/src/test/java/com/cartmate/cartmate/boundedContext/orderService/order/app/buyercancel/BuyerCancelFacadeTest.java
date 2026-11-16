package com.cartmate.cartmate.boundedContext.orderService.order.app.buyercancel;

import com.cartmate.cartmate.boundedContext.orderService.order.app.personal.BuyerCancelStart;
import com.cartmate.cartmate.boundedContext.orderService.order.app.personal.OrderFacade;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.buyercancel.BuyerCancelRequest;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.buyercancel.BuyerCancelRequest.Target;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.buyercancel.BuyerCancelRequestStatus;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.BuyerCancelResult;
import com.cartmate.cartmate.boundedContext.orderService.order.exception.OrderErrorCode;
import com.cartmate.cartmate.boundedContext.orderService.order.out.buyercancel.BuyerCancelRequestRepository;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.shared.order.event.OrderBuyerCancelRequestedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BuyerCancelFacadeTest {

    private static final long ORDER_ID = 100L;
    private static final long BUYER = 1L;
    private static final long REQUEST_ID = 55L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 12, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    private static final Duration RESEND_AFTER = Duration.ofMinutes(10);

    private final BuyerCancelRequestRepository repository = mock(BuyerCancelRequestRepository.class);
    private final OrderFacade orderFacade = mock(OrderFacade.class);
    private final DomainEventPublisher eventPublisher = mock(DomainEventPublisher.class);
    private final BuyerCancelFacade facade = new BuyerCancelFacade(repository, orderFacade, eventPublisher, CLOCK);

    @Test
    @DisplayName("결제 완료 주문의 상품을 고르면 요청 한 건을 남기고, 고른 항목과 판매자를 실은 취소 요청 이벤트를 낸다")
    void request_paidOrder_createsRequestAndPublishesEventWithItems() {
        List<Target> targets = List.of(new Target(501L, 7L), new Target(503L, 8L));
        when(orderFacade.startBuyerCancel(BUYER, ORDER_ID, List.of(501L, 503L)))
                .thenReturn(new BuyerCancelStart(false, targets));
        when(repository.save(any(BuyerCancelRequest.class))).thenAnswer(invocation -> {
            BuyerCancelRequest saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", REQUEST_ID);
            return saved;
        });

        BuyerCancelResult result = facade.request(BUYER, ORDER_ID, List.of(501L, 503L));

        assertThat(result).isEqualTo(BuyerCancelResult.REQUESTED);
        verify(eventPublisher).publish(new OrderBuyerCancelRequestedEvent(REQUEST_ID, ORDER_ID, BUYER, NOW, List.of(
                new OrderBuyerCancelRequestedEvent.Item(501L, 7L), new OrderBuyerCancelRequestedEvent.Item(503L, 8L))));
    }

    @Test
    @DisplayName("처리 중인 취소 요청이 있으면 새 요청을 받지 않는다")
    void request_whilePending_throwsInProgress() {
        when(orderFacade.startBuyerCancel(BUYER, ORDER_ID, List.of(501L)))
                .thenReturn(new BuyerCancelStart(false, List.of(new Target(501L, 7L))));
        when(repository.existsByOrderIdAndStatus(ORDER_ID, BuyerCancelRequestStatus.REQUESTED)).thenReturn(true);

        assertThatThrownBy(() -> facade.request(BUYER, ORDER_ID, List.of(501L)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(OrderErrorCode.ORDER_CANCEL_IN_PROGRESS.getMsg());
        verify(repository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("결제 대기 주문은 바로 취소되고 요청 장부에는 아무것도 남기지 않는다")
    void request_paymentPending_cancelsWholeOrderImmediately() {
        when(orderFacade.startBuyerCancel(BUYER, ORDER_ID, null)).thenReturn(new BuyerCancelStart(true, List.of()));

        BuyerCancelResult result = facade.request(BUYER, ORDER_ID, null);

        assertThat(result).isEqualTo(BuyerCancelResult.CANCELED);
        verifyNoInteractions(repository, eventPublisher);
    }

    @Test
    @DisplayName("응답이 없으면 간격이 지난 뒤에만 같은 요청 ID와 처음 요청 시각으로 다시 보낸다")
    void resend_afterInterval_republishesSameRequestIdAndRequestedAt() {
        LocalDateTime firstRequestedAt = NOW.minusMinutes(30);
        BuyerCancelRequest notDue = BuyerCancelRequest.create(ORDER_ID, BUYER, List.of(new Target(501L, 7L)), NOW.minusMinutes(5));
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(notDue));
        facade.resend(1L, RESEND_AFTER);
        verifyNoInteractions(eventPublisher);

        BuyerCancelRequest due = BuyerCancelRequest.create(ORDER_ID, BUYER, List.of(new Target(501L, 7L)), firstRequestedAt);
        ReflectionTestUtils.setField(due, "id", REQUEST_ID);
        when(repository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(due));

        facade.resend(REQUEST_ID, RESEND_AFTER);

        verify(eventPublisher).publish(new OrderBuyerCancelRequestedEvent(REQUEST_ID, ORDER_ID, BUYER, firstRequestedAt,
                List.of(new OrderBuyerCancelRequestedEvent.Item(501L, 7L))));
        assertThat(due.getLastSentAt()).isEqualTo(NOW);
    }
}
