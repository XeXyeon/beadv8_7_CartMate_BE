package com.cartmate.cartmate.boundedContext.etcService.delivery.in;

import com.cartmate.cartmate.boundedContext.etcService.delivery.app.DeliveryFacade;
import com.cartmate.cartmate.shared.order.event.OrderClaimReturnVerifiedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeliveryOrderClaimEventListener {

    private final DeliveryFacade deliveryFacade;

    @EventListener
    public void handle(OrderClaimReturnVerifiedEvent event) {
        deliveryFacade.applyClaimReturnVerified(event.claimId(), event.accepted(), event.rejectCode());
    }
}
