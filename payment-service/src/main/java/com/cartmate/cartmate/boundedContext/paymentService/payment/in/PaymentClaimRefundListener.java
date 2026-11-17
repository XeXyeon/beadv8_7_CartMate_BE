package com.cartmate.cartmate.boundedContext.paymentService.payment.in;

import com.cartmate.cartmate.boundedContext.paymentService.payment.app.PaymentFacade;
import com.cartmate.cartmate.boundedContext.paymentService.payment.exception.PaymentErrorCode;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.lock.LockErrorCode;
import com.cartmate.cartmate.shared.order.event.OrderClaimRefundRequestedEvent;
import com.cartmate.cartmate.shared.payment.event.ClaimRefundCompletedEvent;
import com.cartmate.cartmate.shared.payment.event.ClaimRefundFailedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 주문 서버가 쿠폰 할인을 반영해 정한 반품 환불 금액을 받아 클레임당 한 번 환불하고 결과를 낸다.
 * 환불 결과(완료·실패) 이벤트는 결제 서버만 낸다. 개인 주문만 온다(공동물품은 반품을 접수할 수 없다).
 */
@Component
@RequiredArgsConstructor
public class PaymentClaimRefundListener {

    private static final Set<String> RETRYABLE_CODES = Set.of(
            LockErrorCode.LOCK_ACQUISITION_FAILED.resultCode(),
            PaymentErrorCode.PAYMENT_REFUND_IN_PROGRESS.resultCode());

    private final PaymentFacade paymentFacade;
    private final DomainEventPublisher eventPublisher;

    @EventListener
    public void handle(OrderClaimRefundRequestedEvent event) {
        // 쿠폰 조건 때문에 0원이면 지갑을 거치지 않고 완료로 알린다. 그래야 클레임이 환불중에서 멈추지 않는다.
        if (event.amount() == 0) {
            eventPublisher.publish(new ClaimRefundCompletedEvent(event.claimId(), event.orderId(), 0L));
            return;
        }
        try {
            paymentFacade.refundByClaim(event.claimId(), event.orderId(), event.buyerMemberId(), event.amount());
        } catch (ServiceException e) {
            if (RETRYABLE_CODES.contains(e.getResultCode())) {
                throw e;
            }
            eventPublisher.publish(new ClaimRefundFailedEvent(event.claimId(), event.orderId(), e.getResultCode()));
            return;
        }
        eventPublisher.publish(new ClaimRefundCompletedEvent(event.claimId(), event.orderId(), event.amount()));
    }
}
