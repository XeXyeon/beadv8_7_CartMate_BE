package com.cartmate.cartmate.boundedContext.orderService.order.in.buyercancel;

import com.cartmate.cartmate.boundedContext.orderService.order.app.buyercancel.BuyerCancelFacade;
import com.cartmate.cartmate.shared.delivery.event.DeliveryBuyerCancelResultEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class DeliveryBuyerCancelResultListener {

    private final BuyerCancelFacade buyerCancelFacade;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void handle(DeliveryBuyerCancelResultEvent event) {
        buyerCancelFacade.applyResult(event);
    }
}
