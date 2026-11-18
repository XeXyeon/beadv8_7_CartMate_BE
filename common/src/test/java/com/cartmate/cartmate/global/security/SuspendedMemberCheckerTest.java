package com.cartmate.cartmate.global.security;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

// Redis 장애가 전체 장애가 되지 않게: 조회 실패는 정지가 아닌 것으로 보고 통과 (사용자 결정, DB 대체 조회 없음)
class SuspendedMemberCheckerTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SuspendedMemberChecker checker = new SuspendedMemberChecker(redis);

    @Test
    void 정지_키가_있으면_정지다() {
        given(redis.hasKey("member:suspended:1")).willReturn(true);

        assertThat(checker.isSuspended(1L)).isTrue();
    }

    @Test
    void Redis_조회가_실패하면_통과시킨다() {
        given(redis.hasKey("member:suspended:1")).willThrow(new RedisConnectionFailureException("down"));

        assertThat(checker.isSuspended(1L)).isFalse();
        assertThat(checker.isSuspended(1L)).isFalse(); // 두 번째부터는 ERROR 로그만 줄이고 결과는 같다
    }
}
