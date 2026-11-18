package com.cartmate.cartmate.boundedContext.productService.config;

import com.cartmate.cartmate.global.kafka.InboundEventBinding;
import com.cartmate.cartmate.global.outbox.OutboxTopics;
import com.cartmate.cartmate.shared.cart.event.IndividualGroupPurchaseClosedEvent;
import com.cartmate.cartmate.shared.cart.event.IndividualGroupPurchaseOpenedEvent;
import com.cartmate.cartmate.shared.cart.event.IndividualGroupPurchaseUpdatedEvent;
import com.cartmate.cartmate.shared.delivery.event.DeliveryConfirmedEvent;
import com.cartmate.cartmate.shared.member.event.MemberSignedUpEvent;
import com.cartmate.cartmate.shared.member.event.MemberWithdrawalRequestedEvent;
import com.cartmate.cartmate.shared.member.event.SellerRegisteredEvent;
import com.cartmate.cartmate.shared.trust.event.SellerTrustScoreChangedEvent;
import com.cartmate.cartmate.shared.order.event.OrderCanceledEvent;
import com.cartmate.cartmate.shared.order.event.OrderCreatedEvent;
import com.cartmate.cartmate.shared.order.event.OrderPaidEvent;
import com.cartmate.cartmate.shared.order.event.OrderSellerItemsCanceledEvent;
import com.cartmate.cartmate.shared.order.event.SharedGroupOrderCanceledEvent;
import com.cartmate.cartmate.shared.order.event.SharedGroupOrderConfirmedEvent;
import com.cartmate.cartmate.shared.order.event.SharedGroupOrderSellerItemsCanceledEvent;
import com.cartmate.cartmate.shared.wallet.event.PaybackDepositFailedEvent;
import com.cartmate.cartmate.shared.wallet.event.PaybackDepositedEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "cartmate.kafka.enabled", havingValue = "true")
public class ProductServiceKafkaConfig {

    @Bean
    public InboundEventBinding individualGroupPurchaseOpenedBinding() {
        return binding(IndividualGroupPurchaseOpenedEvent.class);
    }

    @Bean
    public InboundEventBinding individualGroupPurchaseUpdatedBinding() {
        return binding(IndividualGroupPurchaseUpdatedEvent.class);
    }

    @Bean
    public InboundEventBinding individualGroupPurchaseClosedBinding() {
        return binding(IndividualGroupPurchaseClosedEvent.class);
    }

    @Bean
    public InboundEventBinding orderCreatedBinding() {
        return binding(OrderCreatedEvent.class);
    }

    @Bean
    public InboundEventBinding orderPaidBinding() {
        return binding(OrderPaidEvent.class);
    }

    @Bean
    public InboundEventBinding orderCanceledBinding() {
        return binding(OrderCanceledEvent.class);
    }

    @Bean
    public InboundEventBinding orderSellerItemsCanceledBinding() {
        return binding(OrderSellerItemsCanceledEvent.class);
    }

    @Bean
    public InboundEventBinding deliveryConfirmedBinding() {
        return binding(DeliveryConfirmedEvent.class);
    }

    // 공동 주문 확정 → 재고 판매 확정 (ProductEventListener), 리뷰 자격용 구매 기록 (SalesPostEventListener)
    @Bean
    public InboundEventBinding sharedGroupOrderConfirmedBinding() {
        return binding(SharedGroupOrderConfirmedEvent.class);
    }

    // 공동 주문 취소 → 선점 해제 · 재고 복구 (ProductEventListener)
    @Bean
    public InboundEventBinding sharedGroupOrderCanceledBinding() {
        return binding(SharedGroupOrderCanceledEvent.class);
    }

    // 확정된 공동 주문의 판매자 몫 취소 → 그 판매자 재고 복구 (ProductEventListener)
    @Bean
    public InboundEventBinding sharedGroupOrderSellerItemsCanceledBinding() {
        return binding(SharedGroupOrderSellerItemsCanceledEvent.class);
    }

    @Bean
    public InboundEventBinding paybackDepositedBinding() {
        return binding(PaybackDepositedEvent.class);
    }

    @Bean
    public InboundEventBinding paybackDepositFailedBinding() {
        return binding(PaybackDepositFailedEvent.class);
    }

    @Bean
    public InboundEventBinding memberSignedUpBinding() {
        return binding(MemberSignedUpEvent.class);
    }

    // 판매자 등록 → 판매글에 보여줄 판매자 복제 (SalesPostEventListener, 멱등)
    @Bean
    public InboundEventBinding sellerRegisteredBinding() {
        return binding(SellerRegisteredEvent.class);
    }

    // 탈퇴 신청 → 그 판매자의 판매 중 판매글 판매 중지 (SalesPostMemberEventListener)
    @Bean
    public InboundEventBinding memberWithdrawalRequestedBinding() {
        return binding(MemberWithdrawalRequestedEvent.class);
    }

    // 신뢰도 변경 → 판매글에 보여줄 판매자 신뢰도 복제 (SalesPostEventListener, 늦게 온 이전 점수는 무시)
    @Bean
    public InboundEventBinding sellerTrustScoreChangedBinding() {
        return binding(SellerTrustScoreChangedEvent.class);
    }

    private static InboundEventBinding binding(Class<?> eventType) {
        return new InboundEventBinding(OutboxTopics.of(eventType), eventType);
    }
}
