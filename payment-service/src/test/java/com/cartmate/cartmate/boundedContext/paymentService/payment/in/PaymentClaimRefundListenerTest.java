package com.cartmate.cartmate.boundedContext.paymentService.payment.in;

import com.cartmate.cartmate.boundedContext.paymentService.payment.app.PaymentFacade;
import com.cartmate.cartmate.boundedContext.paymentService.payment.exception.PaymentErrorCode;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.lock.LockErrorCode;
import com.cartmate.cartmate.shared.order.event.OrderClaimRefundRequestedEvent;
import com.cartmate.cartmate.shared.payment.event.ClaimRefundCompletedEvent;
import com.cartmate.cartmate.shared.payment.event.ClaimRefundFailedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PaymentClaimRefundListenerTest {

    private final PaymentFacade paymentFacade = mock(PaymentFacade.class);
    private final DomainEventPublisher eventPublisher = mock(DomainEventPublisher.class);
    private final PaymentClaimRefundListener listener = new PaymentClaimRefundListener(paymentFacade, eventPublisher);

    private OrderClaimRefundRequestedEvent event(long amount) {
        return new OrderClaimRefundRequestedEvent(7L, 100L, 10L, amount);
    }

    @Test
    @DisplayName("환불에 성공하면 환불 완료 결과를 기록한다")
    void publishesCompletedWhenRefunded() {
        listener.handle(event(3_000L));

        verify(paymentFacade).refundByClaim(7L, 100L, 10L, 3_000L);
        verify(eventPublisher).publish(new ClaimRefundCompletedEvent(7L, 100L, 3_000L));
    }

    @Test
    @DisplayName("환불 금액이 0원이면 지갑을 거치지 않고 환불 완료 결과를 기록한다")
    void zeroAmountCompletesWithoutRefunding() {
        listener.handle(event(0L));

        verifyNoInteractions(paymentFacade);
        verify(eventPublisher).publish(new ClaimRefundCompletedEvent(7L, 100L, 0L));
    }

    @Test
    @DisplayName("다시 해도 안 되는 업무 실패는 환불 실패 결과로 기록하고 예외를 올리지 않는다")
    void publishesFailedOnBusinessFailure() {
        doThrow(new ServiceException(PaymentErrorCode.PAYMENT_REFUND_EXCEEDS_REMAINING))
                .when(paymentFacade).refundByClaim(anyLong(), anyLong(), anyLong(), anyLong());

        listener.handle(event(3_000L));

        verify(eventPublisher).publish(new ClaimRefundFailedEvent(
                7L, 100L, PaymentErrorCode.PAYMENT_REFUND_EXCEEDS_REMAINING.resultCode()));
        verify(eventPublisher, never()).publish(any(ClaimRefundCompletedEvent.class));
    }

    @Test
    @DisplayName("락 획득 실패와 같은 환불 처리 중은 결과를 기록하지 않고 예외를 올려 재시도하게 한다")
    void rethrowsRetryableFailures() {
        doThrow(new ServiceException(LockErrorCode.LOCK_ACQUISITION_FAILED))
                .when(paymentFacade).refundByClaim(anyLong(), anyLong(), anyLong(), anyLong());
        assertThatThrownBy(() -> listener.handle(event(3_000L)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("LOCK_ACQUISITION_FAILED");

        doThrow(new ServiceException(PaymentErrorCode.PAYMENT_REFUND_IN_PROGRESS))
                .when(paymentFacade).refundByClaim(anyLong(), anyLong(), anyLong(), anyLong());
        assertThatThrownBy(() -> listener.handle(event(3_000L)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("PAYMENT_REFUND_IN_PROGRESS");

        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("예상하지 못한 예외는 실패로 확정하지 않고 그대로 올린다")
    void rethrowsUnexpectedExceptions() {
        doThrow(new IllegalStateException("db down"))
                .when(paymentFacade).refundByClaim(anyLong(), anyLong(), anyLong(), anyLong());

        assertThatThrownBy(() -> listener.handle(event(3_000L)))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(eventPublisher);
    }
}
