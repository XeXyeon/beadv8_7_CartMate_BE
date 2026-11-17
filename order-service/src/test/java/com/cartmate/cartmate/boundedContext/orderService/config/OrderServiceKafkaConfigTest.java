package com.cartmate.cartmate.boundedContext.orderService.config;

import com.cartmate.cartmate.global.kafka.InboundEventBinding;
import com.cartmate.cartmate.global.outbox.OutboxTopics;
import com.cartmate.cartmate.shared.delivery.event.DeliveryClaimReturnApprovedEvent;
import com.cartmate.cartmate.shared.delivery.event.DeliveryClaimReturnRequestedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderServiceKafkaConfigTest {

    @Test
    @DisplayName("배송 서버의 반품 접수 검사 요청과 반품 승인 이벤트를 인박스로 받도록 바인딩한다")
    void bindsClaimReturnEvents() {
        assertThat(bindings())
                .contains(new InboundEventBinding(OutboxTopics.of(DeliveryClaimReturnRequestedEvent.class), DeliveryClaimReturnRequestedEvent.class))
                .contains(new InboundEventBinding(OutboxTopics.of(DeliveryClaimReturnApprovedEvent.class), DeliveryClaimReturnApprovedEvent.class));
    }

    private static List<InboundEventBinding> bindings() {
        OrderServiceKafkaConfig config = new OrderServiceKafkaConfig();
        return Arrays.stream(OrderServiceKafkaConfig.class.getDeclaredMethods())
                .filter(method -> method.getReturnType() == InboundEventBinding.class && method.getParameterCount() == 0)
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .map(method -> invoke(config, method))
                .toList();
    }

    private static InboundEventBinding invoke(OrderServiceKafkaConfig config, Method method) {
        try {
            return (InboundEventBinding) method.invoke(config);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
