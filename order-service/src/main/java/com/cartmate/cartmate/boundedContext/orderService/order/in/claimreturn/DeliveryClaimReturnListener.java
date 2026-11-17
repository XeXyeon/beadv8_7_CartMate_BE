package com.cartmate.cartmate.boundedContext.orderService.order.in.claimreturn;

import com.cartmate.cartmate.boundedContext.orderService.order.app.claimreturn.ClaimReturnFacade;
import com.cartmate.cartmate.shared.delivery.event.DeliveryClaimReturnApprovedEvent;
import com.cartmate.cartmate.shared.delivery.event.DeliveryClaimReturnRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class DeliveryClaimReturnListener {

    private final ClaimReturnFacade claimReturnFacade;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void handle(DeliveryClaimReturnRequestedEvent event) {
        claimReturnFacade.verify(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void handle(DeliveryClaimReturnApprovedEvent event) {
        claimReturnFacade.apply(event);
    }
}
