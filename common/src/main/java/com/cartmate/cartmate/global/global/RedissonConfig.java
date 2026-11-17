package com.cartmate.cartmate.global.global;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

// Redisson을 의존성으로 넣은 서비스(payment · etc)에서만 만든다. 다른 서비스는 Redisson 클래스가 없어 건너뛴다
@Configuration
@ConditionalOnClass(name = "org.redisson.api.RedissonClient")
public class RedissonConfig {

    // Redisson은 분산 락(tryLock · unlock)에만 쓴다. 기본값은 범용 설정이라 락 용도에 맞게 줄인다. 근거는 vCPU 2 · 락 경로 15 TPS 기준

    // Redis 통신(Netty) 스레드 최대 수. 기본 32. 0이면 Netty가 vCPU × 2로 계산하는데, vCPU 2 기준 그 값이 4
    static final int NETTY_THREADS = 4;
    // 락 해제 메시지 리스너(세마포어 release 호출)를 실행하는 스레드 수. 기본 16. 0이면 Redisson이 vCPU × 2로 계산하는데, vCPU 2 기준 그 값이 4
    static final int THREADS = 4;
    // 첫 락 이후 항상 열어 두는 커넥션 수. 기본 24. 평소 동시 사용이 1개 미만(15 TPS × 명령 2개 × 2ms = 0.06개)
    static final int CONNECTION_MINIMUM_IDLE_SIZE = 2;
    // 일반 명령 커넥션 최대 수. 기본 64. 같은 ms에 16개 넘게 몰려도 대기는 명령 1개(약 2ms)라 timeout(3000ms)에 닿지 않는다
    static final int CONNECTION_POOL_SIZE = 16;
    // 구독(락 해제 메시지) 커넥션 최대 수. 기본 50. 커넥션 1개가 채널 5개를 처리해 10개면 서로 다른 키 50개 대기까지 처리한다
    static final int SUBSCRIPTION_CONNECTION_POOL_SIZE = 10;

    // 분산 락은 키가 지워지면 안 되므로 core Redis에 연결한다. Redisson은 생성 즉시 접속하므로 @Lazy로 첫 사용 시점까지 미룬다.
    @Bean(destroyMethod = "shutdown")
    @Lazy
    public RedissonClient redissonClient(RedisProperties properties) {
        return Redisson.create(buildConfig(properties));
    }

    // 접속 없이 설정값만 만든다. 테스트에서 값을 확인할 때도 쓴다
    static Config buildConfig(RedisProperties properties) {
        RedisProperties.Node core = properties.core();
        Config config = new Config();
        config.setNettyThreads(NETTY_THREADS);
        config.setThreads(THREADS);
        config.useSingleServer()
                .setAddress("redis://" + core.host() + ":" + core.port())
                .setDatabase(properties.database())
                .setConnectionMinimumIdleSize(CONNECTION_MINIMUM_IDLE_SIZE)
                .setConnectionPoolSize(CONNECTION_POOL_SIZE)
                .setSubscriptionConnectionPoolSize(SUBSCRIPTION_CONNECTION_POOL_SIZE);
        if (properties.password() != null && !properties.password().isBlank()) {
            config.setPassword(properties.password());
        }
        return config;
    }
}
