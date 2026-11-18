package com.cartmate.cartmate.global.security;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 정지 회원인지 Redis(core)의 {@code member:suspended:{memberId}} 키로 확인한다. 모든 서비스의 JwtAuthenticationFilter가 요청마다 부른다.
 * 키는 회원 서비스만 쓴다 (정지·해제 이벤트를 Outbox 릴레이로 받아 DB 상태대로 SET/DEL, 서버 시작 때 한 번 맞춤).
 *
 * <p>Redis가 느리거나 죽어도 전체 응답이 늦어지지 않게 이 조회만 짧게 끊는다(연결·명령 {@link #TIMEOUT}, 끊겨 있으면 기다리지 않고 거절).
 * 조회에 실패하면 정지가 아닌 것으로 보고 통과시키고 ERROR 로그를 남긴다 (Sentry·Discord 알림).
 * 그래도 정지 회원은 Refresh Token이 모두 폐기되고 로그인이 막혀 있어 Access Token이 끝나면(최대 15분) 끊긴다.
 */
@Slf4j
@Component
public class SuspendedMemberChecker implements DisposableBean {

    public static final String KEY_PREFIX = "member:suspended:";
    static final Duration TIMEOUT = Duration.ofMillis(200);
    // 장애 중 요청마다 ERROR를 남기면 알림이 넘치므로 서버당 10초에 한 번만 남긴다 (5분 10건 알림 기준은 넘김)
    private static final long ERROR_LOG_INTERVAL_MILLIS = 10_000;

    private final StringRedisTemplate redis;
    private final LettuceConnectionFactory ownConnectionFactory; // 이 조회 전용으로 만든 연결 (닫을 때 정리)
    private final AtomicLong lastErrorLoggedAt = new AtomicLong();
    private final AtomicLong suppressedErrors = new AtomicLong();

    // 서비스의 기본 Redis 연결(회원·상품·결제·기타는 core, 주문은 Spring Boot 기본 설정)과 같은 주소·DB 번호로,
    // 시간 제한만 짧은 연결을 따로 만든다. 기본 연결의 시간 제한(인증 코드 저장 등)은 그대로 둔다
    @Autowired
    public SuspendedMemberChecker(ObjectProvider<RedisConnectionFactory> connectionFactories) {
        this(connectionFactories.getIfAvailable());
    }

    public SuspendedMemberChecker(RedisConnectionFactory base) {
        this.ownConnectionFactory = base instanceof LettuceConnectionFactory lettuce ? fastConnectionFactory(lettuce) : null;
        RedisConnectionFactory target = ownConnectionFactory != null ? ownConnectionFactory : base;
        this.redis = target == null ? null : new StringRedisTemplate(target);
        if (redis == null) {
            log.warn("Redis 연결이 없어 정지 회원 즉시 차단을 하지 않는다 (Access Token 만료로만 끊김)");
        } else if (ownConnectionFactory == null) {
            log.warn("Lettuce 연결이 아니라 정지 회원 조회에 짧은 시간 제한을 걸지 못했다: {}", base.getClass().getName());
        }
    }

    // 테스트에서 템플릿을 바꿔 끼우기 위한 생성자
    SuspendedMemberChecker(StringRedisTemplate redis) {
        this.redis = redis;
        this.ownConnectionFactory = null;
    }

    public static String keyOf(Long memberId) {
        return KEY_PREFIX + memberId;
    }

    public boolean isSuspended(Long memberId) {
        if (redis == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(keyOf(memberId)));
        } catch (RuntimeException e) {
            logFailure(memberId, e);
            return false; // Redis 장애가 전체 장애가 되지 않게 통과시킨다 (사용자 결정: DB·내부 API로 대신 묻지 않음)
        }
    }

    private void logFailure(Long memberId, RuntimeException e) {
        long now = System.currentTimeMillis();
        long last = lastErrorLoggedAt.get();
        if (now - last < ERROR_LOG_INTERVAL_MILLIS || !lastErrorLoggedAt.compareAndSet(last, now)) {
            suppressedErrors.incrementAndGet();
            return;
        }
        log.atError().addKeyValue("event", "member.suspended.check-failed")
                .addKeyValue("memberId", memberId)
                .addKeyValue("suppressed", suppressedErrors.getAndSet(0))
                .setCause(e)
                .log("정지 회원 조회(Redis) 실패 → 차단하지 않고 통과시킴. 정지 회원도 Access Token 만료 전까지 이용할 수 있다: memberId={}",
                        memberId);
    }

    private static LettuceConnectionFactory fastConnectionFactory(LettuceConnectionFactory base) {
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(TIMEOUT)
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(TIMEOUT).build())
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .build())
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(base.getStandaloneConfiguration(), clientConfig);
        factory.afterPropertiesSet();
        factory.start(); // 실제 연결은 첫 조회 때 맺는다 (Redis가 없어도 서버는 뜬다)
        return factory;
    }

    @Override
    public void destroy() {
        if (ownConnectionFactory != null) {
            ownConnectionFactory.destroy();
        }
    }
}
