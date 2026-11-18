package com.cartmate.cartmate.boundedContext.memberService.member.out;

import com.cartmate.cartmate.global.security.SuspendedMemberChecker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 정지 회원 표시 키(member:suspended:{memberId})를 Redis(core)에 쓴다. 모든 서비스의 필터가 이 키로 막는다 ({@link SuspendedMemberChecker}).
 * 쓰기는 회원 서비스만 한다. 실패하면 예외를 그대로 던진다 (Outbox 릴레이가 다시 시도).
 */
@Component
public class SuspendedMemberStore {

    private static final String SUSPENDED = "1";

    private final StringRedisTemplate redis;

    @Autowired
    public SuspendedMemberStore(@Qualifier("redisCoreConnectionFactory") RedisConnectionFactory connectionFactory) {
        this(new StringRedisTemplate(connectionFactory));
    }

    // 테스트에서 템플릿을 바꿔 끼우기 위한 생성자
    SuspendedMemberStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // 정지는 해제 이벤트로 지운다 (기간 정지도 만료 스케줄러가 해제 → 이벤트). 만료 시간은 두지 않는다
    public void markSuspended(Long memberId) {
        redis.opsForValue().set(SuspendedMemberChecker.keyOf(memberId), SUSPENDED);
    }

    public void clear(Long memberId) {
        redis.delete(SuspendedMemberChecker.keyOf(memberId));
    }

    // 지금 Redis에 정지로 표시된 회원 ID (서버 시작 때 DB와 맞추는 데만 쓴다)
    public List<Long> findMarkedMemberIds() {
        List<Long> ids = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(SuspendedMemberChecker.KEY_PREFIX + "*").count(500).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            cursor.forEachRemaining(key -> {
                String id = key.substring(SuspendedMemberChecker.KEY_PREFIX.length());
                try {
                    ids.add(Long.valueOf(id));
                } catch (NumberFormatException ignored) {
                    // 형식이 다른 키는 건드리지 않는다
                }
            });
        }
        return ids;
    }
}
