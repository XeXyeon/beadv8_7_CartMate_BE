package com.cartmate.cartmate.global.global;

import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;

import static org.assertj.core.api.Assertions.assertThat;

// Redisson 설정이 락 용도로 줄인 값으로 만들어지는지. Redis에 접속하지 않고 Config만 확인한다
class RedissonConfigTest {

    private final RedisProperties properties = new RedisProperties("secret", 1,
            new RedisProperties.Node("cache-host", 6379), new RedisProperties.Node("core-host", 6380));

    @Test
    void buildConfig_reducesThreads() {
        Config config = RedissonConfig.buildConfig(properties);

        assertThat(config.getNettyThreads()).isEqualTo(4);
        assertThat(config.getThreads()).isEqualTo(4);
    }

    @Test
    void buildConfig_reducesConnections() {
        SingleServerConfig server = RedissonConfig.buildConfig(properties).useSingleServer();

        assertThat(server.getConnectionMinimumIdleSize()).isEqualTo(2);
        assertThat(server.getConnectionPoolSize()).isEqualTo(16);
        assertThat(server.getSubscriptionConnectionPoolSize()).isEqualTo(10);
        assertThat(server.getSubscriptionConnectionMinimumIdleSize()).isEqualTo(1);   // 바꾸지 않은 값
    }

    // 분산 락은 core Redis에 연결한다
    @Test
    void buildConfig_connectsToCoreRedis() {
        SingleServerConfig server = RedissonConfig.buildConfig(properties).useSingleServer();

        assertThat(server.getAddress()).isEqualTo("redis://core-host:6380");
        assertThat(server.getDatabase()).isEqualTo(1);
    }
}
