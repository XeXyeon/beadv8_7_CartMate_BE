package com.cartmate.cartmate.global.security;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

// 회원 서비스가 아닌 서비스(결제)의 공통 필터가 Redis 키(member:suspended:{id})만 보고 정지 회원을 막는지,
// Redis가 죽어 있으면 짧게 끊고 통과시키는지 (Docker 필요)
class SuspendedMemberFilterRedisTest {

    private static final long MEMBER = 700_000_001L;
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory serviceConnection; // 서비스의 기본(core) 연결 역할
    private static StringRedisTemplate redis;                 // 회원 서비스가 키를 쓰는 역할

    private final JwtProvider jwtProvider = new JwtProvider("test-jwt-secret-test-jwt-secret-test-jwt-secret", 1800);

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        serviceConnection = connection(REDIS.getHost(), REDIS.getMappedPort(6379));
        redis = new StringRedisTemplate(serviceConnection);
    }

    @AfterAll
    static void stopRedis() {
        serviceConnection.destroy();
        REDIS.stop();
    }

    @AfterEach
    void clear() {
        redis.delete(SuspendedMemberChecker.keyOf(MEMBER));
    }

    @Test
    void 정지_키가_있으면_남은_토큰이_유효해도_인증하지_않고_키가_지워지면_다시_인증한다() throws Exception {
        SuspendedMemberChecker checker = new SuspendedMemberChecker(serviceConnection);
        try {
            redis.opsForValue().set(SuspendedMemberChecker.keyOf(MEMBER), "1");
            assertThat(authenticate(checker)).isNull();

            redis.delete(SuspendedMemberChecker.keyOf(MEMBER));
            assertThat(authenticate(checker)).isNotNull();
        } finally {
            checker.destroy();
        }
    }

    @Test
    void Redis에_닿지_않으면_차단하지_않고_통과시킨다() throws Exception {
        LettuceConnectionFactory dead = connection("localhost", 1); // 아무도 듣지 않는 포트
        SuspendedMemberChecker checker = new SuspendedMemberChecker(dead);
        try {
            long started = System.nanoTime();
            Authentication authentication = authenticate(checker);
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

            assertThat(authentication).isNotNull();
            assertThat(elapsedMillis).isLessThan(2_000); // 요청이 Redis 장애에 오래 붙잡히지 않는다
        } finally {
            checker.destroy();
            dead.destroy();
        }
    }

    // 필터를 한 번 통과시키고, 다음 단계에서 본 인증 정보를 돌려준다 (정지면 null)
    private Authentication authenticate(SuspendedMemberChecker checker) throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtProvider, checker);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/wallets/me");
        request.addHeader("Authorization", "Bearer " + jwtProvider.createAccessToken(MEMBER, "m@cartmate.test", "BUYER", null));
        AtomicReference<Authentication> seen = new AtomicReference<>();
        try {
            filter.doFilter(request, new MockHttpServletResponse(),
                    (req, res) -> seen.set(SecurityContextHolder.getContext().getAuthentication()));
        } finally {
            SecurityContextHolder.clearContext();
        }
        return seen.get();
    }

    private static LettuceConnectionFactory connection(String host, int port) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(host, port);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }
}
