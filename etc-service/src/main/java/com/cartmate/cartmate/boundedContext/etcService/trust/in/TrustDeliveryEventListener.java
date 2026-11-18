package com.cartmate.cartmate.boundedContext.etcService.trust.in;

import com.cartmate.cartmate.boundedContext.etcService.trust.app.TrustFacade;
import com.cartmate.cartmate.global.event.OutboxMessageRelayed;
import com.cartmate.cartmate.shared.delivery.event.DeliveryClaimCompletedEvent;
import com.cartmate.cartmate.shared.delivery.event.DeliveryConfirmedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 같은 서비스의 delivery 모듈이 Outbox로 보낸 이벤트를 받아 신뢰도 이력을 남긴다.
 *
 * <p>Kafka가 켜져 있으면 Kafka로 온 것만(EtcServiceRelayConfig 구독), 꺼져 있으면 같은 앱 안에서 온 것만 받는다
 * (같은 이벤트를 두 번 처리하지 않게). 이력은 (유형, 식별 키) 유니크라 같은 이벤트가 다시 와도 한 번만 남는다(멱등).
 *
 * <p>예외를 잡아 삼키지 않는다. 실패하면 Kafka가 다시 보내고(재시도 후 DLT), 꺼져 있을 때는 릴레이가 다시 보낸다.
 */
@Component
@RequiredArgsConstructor
public class TrustDeliveryEventListener {

    private final TrustFacade trustFacade;

    @TransactionalEventListener(fallbackExecution = true, condition = "@relayedEventRouting.accepts(#message)")
    public void handle(OutboxMessageRelayed message) {
        if (message.event() instanceof DeliveryConfirmedEvent event) {
            trustFacade.recordPurchaseConfirmed(event);
        }
        if (message.event() instanceof DeliveryClaimCompletedEvent event) {
            trustFacade.recordClaimCompleted(event);
        }
    }
}
