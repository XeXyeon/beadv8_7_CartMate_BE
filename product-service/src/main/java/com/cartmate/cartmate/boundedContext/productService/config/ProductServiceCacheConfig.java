package com.cartmate.cartmate.boundedContext.productService.config;

import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostDetailInventoryLoader;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SalesPostDetailInventories;
import com.cartmate.cartmate.global.global.RedisCacheCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

// 상품 서비스 캐시 설정. 공통 기본값(10분, JSON)과 다른 캐시만 여기서 정한다
@Configuration
public class ProductServiceCacheConfig {

    // 판매글 상세의 선택 목록 · 재고 구성(수량 제외): 판매글 수정 때 지운다. 지우기와 엇갈려 수정 전 값이 다시 들어가거나
    // 지우기를 놓쳐도(Redis 일시 장애 등) 가격 · 옵션 이름이 최대 1분만 예전 값으로 보이게 짧게 둔다.
    // 다시 읽기는 판매글당 1분에 한 번이라 부담이 작다. 남은 수량은 캐시하지 않는다(SalesPostDetailInventoryLoader)
    static final Duration DETAIL_INVENTORY_TTL = Duration.ofMinutes(1);

    @Bean
    public RedisCacheCustomizer productServiceCacheCustomizer() {
        return builder -> builder.withCacheConfiguration(SalesPostDetailInventoryLoader.CACHE_NAME,
                RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(DETAIL_INVENTORY_TTL)
                        // 값 타입이 정해져 있어 타입 정보(@class) 없이 그 타입으로 읽는다
                        .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                                new JacksonJsonRedisSerializer<>(SalesPostDetailInventories.class))));
    }
}
