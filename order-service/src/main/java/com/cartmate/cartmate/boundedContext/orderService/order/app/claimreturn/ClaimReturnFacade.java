package com.cartmate.cartmate.boundedContext.orderService.order.app.claimreturn;

import com.cartmate.cartmate.boundedContext.orderService.order.app.personal.OrderFacade;
import com.cartmate.cartmate.shared.delivery.event.DeliveryClaimReturnApprovedEvent;
import com.cartmate.cartmate.shared.delivery.event.DeliveryClaimReturnRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반품: 배송 서버가 구매자 사정 반품을 받기 전에 묻는 접수 검사에 답하고,
 * 판매자가 승인한 반품의 환불 금액을 정해 결제 서버에 요청한다. 개인 주문만 다룬다(공동물품은 반품을 접수할 수 없다).
 */
@Service
@RequiredArgsConstructor
public class ClaimReturnFacade {

    private final OrderFacade orderFacade;

    @Transactional
    public void verify(DeliveryClaimReturnRequestedEvent event) {
        orderFacade.verifyReturn(event);
    }

    @Transactional
    public void apply(DeliveryClaimReturnApprovedEvent event) {
        orderFacade.applyReturn(event);
    }
}
