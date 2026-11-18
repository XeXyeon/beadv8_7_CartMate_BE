package com.cartmate.cartmate.boundedContext.etcService.config;

import com.cartmate.cartmate.global.kafka.RelaySubscription;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * etc-service가 스스로 보내고 스스로 받는 이벤트(delivery → trust)를 Kafka로 다시 받는다.
 *
 * <p>Kafka를 켜면 outbox 이벤트가 같은 앱 안과 Kafka 양쪽으로 나간다. 받는 쪽은
 * {@code @relayedEventRouting.accepts(#message)} 조건으로 Kafka에서 온 것만 처리한다(product · order-service와 같은 방식).
 * 그룹을 따로 둬서 다른 서비스 이벤트를 받는 etc-service 그룹과 오프셋이 섞이지 않게 한다.
 * trust를 서비스로 떼어 내면 받는 쪽 InboundEventBinding으로 바꾸면 된다.
 */
@Configuration
@ConditionalOnProperty(name = "cartmate.kafka.enabled", havingValue = "true")
public class EtcServiceRelayConfig {

    public static final String RELAY_GROUP_ID = "etc-service-relay";

    @Bean
    public RelaySubscription etcServiceRelaySubscription() {
        return new RelaySubscription(RELAY_GROUP_ID, Set.of("delivery"));
    }
}
