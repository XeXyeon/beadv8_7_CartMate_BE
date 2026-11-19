package com.cartmate.cartmate.boundedContext.memberService.member.out;

import com.cartmate.cartmate.global.security.JwtProvider;
import com.cartmate.cartmate.global.security.SuspendedMemberChecker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 막 탈퇴를 신청한 회원 표시 키(member:withdrawal-pending:{memberId})를 Redis(core)에 쓴다.
 * 모든 서비스의 WithdrawalPendingFilter가 wp 없는 토큰(신청 전에 받은 토큰, 다른 기기)의 변경 요청을 이 키로 막는다.
 *
 * <p>신청 전에 받은 토큰은 Access Token 수명이 지나면 모두 끝나고, 그 뒤 재발급된 토큰에는 wp가 붙는다.
 * 그래서 키는 Access Token 수명 + 여유만큼만 두고 저절로 지워지게 한다 (서버 시작 때 DB와 맞출 필요가 없다).
 */
@Component
public class WithdrawalPendingStore {

    private static final String PENDING = "1";
    static final Duration MARGIN = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    @Autowired
    public WithdrawalPendingStore(@Qualifier("redisCoreConnectionFactory") RedisConnectionFactory connectionFactory,
                                  JwtProvider jwtProvider) {
        this(new StringRedisTemplate(connectionFactory),
                Duration.ofSeconds(jwtProvider.getAccessTokenExpireSeconds()).plus(MARGIN));
    }

    // 테스트에서 템플릿을 바꿔 끼우기 위한 생성자
    WithdrawalPendingStore(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    // 실패하면 예외를 그대로 던진다 (Outbox 릴레이가 다시 시도)
    public void mark(Long memberId) {
        redis.opsForValue().set(SuspendedMemberChecker.withdrawalPendingKeyOf(memberId), PENDING, ttl);
    }

    public void clear(Long memberId) {
        redis.delete(SuspendedMemberChecker.withdrawalPendingKeyOf(memberId));
    }
}
