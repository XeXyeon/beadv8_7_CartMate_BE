package com.cartmate.cartmate.boundedContext.productService.search.in;

import com.cartmate.cartmate.boundedContext.productService.search.app.SalesPostIndexHealth;
import com.cartmate.cartmate.boundedContext.productService.search.app.SalesPostReindexUseCase;
import com.cartmate.cartmate.boundedContext.productService.search.out.SalesPostIndexAdmin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 검색 색인이 낡았고 ES가 응답하면 전체 재색인한다. ES 장애가 풀리면 1~2분 안에 색인이 DB와 같아진다. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cartmate.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SalesPostSearchIndexRecoveryScheduler {

    private static final long INTERVAL_MILLIS = 60_000L;

    private final SalesPostIndexHealth indexHealth;
    private final SalesPostIndexAdmin indexAdmin;
    private final SalesPostReindexUseCase reindexUseCase;

    // 재색인 자체가 DB 락(shedlock)을 잡으므로 여기서는 따로 잡지 않는다
    @Scheduled(fixedDelay = INTERVAL_MILLIS, initialDelay = INTERVAL_MILLIS)
    public void reindexIfStale() {
        if (!indexHealth.isStale() || !indexAdmin.isAvailable()) {
            return;
        }
        try {
            reindexUseCase.reindex();
        } catch (RuntimeException e) {
            log.warn("판매글 검색 색인 복구 재색인 실패, 다음 실행에서 다시 시도", e);
        }
    }
}
