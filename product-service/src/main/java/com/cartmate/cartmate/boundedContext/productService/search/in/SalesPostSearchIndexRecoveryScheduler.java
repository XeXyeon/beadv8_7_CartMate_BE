package com.cartmate.cartmate.boundedContext.productService.search.in;

import com.cartmate.cartmate.boundedContext.productService.search.app.SalesPostIndexHealth;
import com.cartmate.cartmate.boundedContext.productService.search.app.SalesPostReindexUseCase;
import com.cartmate.cartmate.boundedContext.productService.search.out.SalesPostIndexAdmin;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 검색 색인이 낡았고 ES가 응답하면 전체 재색인한다. ES 장애가 풀리면 1~2분 안에 색인이 DB와 같아진다. */
@Slf4j
@Component
@ConditionalOnProperty(name = "cartmate.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SalesPostSearchIndexRecoveryScheduler {

    private static final long INTERVAL_MILLIS = 60_000L;

    private final SalesPostIndexHealth indexHealth;
    private final SalesPostIndexAdmin indexAdmin;
    private final SalesPostReindexUseCase reindexUseCase;
    // 재색인은 오래 걸릴 수 있어 전용 스레드에서 돌린다. 스케줄러 스레드는 outbox 릴레이 등 다른 작업과 같이 쓰는 하나뿐이다
    // (실행기를 빈으로 만들면 스프링 부트가 기본 작업 실행기를 만들지 않으므로 여기서 직접 만든다)
    private final Executor reindexExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    public SalesPostSearchIndexRecoveryScheduler(SalesPostIndexHealth indexHealth, SalesPostIndexAdmin indexAdmin,
                                                 SalesPostReindexUseCase reindexUseCase) {
        this(indexHealth, indexAdmin, reindexUseCase,
                Executors.newSingleThreadExecutor(Thread.ofPlatform().name("sales-post-reindex").daemon().factory()));
    }

    SalesPostSearchIndexRecoveryScheduler(SalesPostIndexHealth indexHealth, SalesPostIndexAdmin indexAdmin,
                                          SalesPostReindexUseCase reindexUseCase, Executor reindexExecutor) {
        this.indexHealth = indexHealth;
        this.indexAdmin = indexAdmin;
        this.reindexUseCase = reindexUseCase;
        this.reindexExecutor = reindexExecutor;
    }

    // 재색인 자체가 DB 락(shedlock)을 잡으므로 여기서는 따로 잡지 않는다
    @Scheduled(fixedDelay = INTERVAL_MILLIS, initialDelay = INTERVAL_MILLIS)
    public void reindexIfStale() {
        // 이미 도는 중이면 또 넘기지 않는다. ES 확인(ping)도 전용 스레드에서 한다
        if (!indexHealth.isStale() || !running.compareAndSet(false, true)) {
            return;
        }
        reindexExecutor.execute(() -> {
            try {
                if (indexAdmin.isAvailable()) {
                    reindexUseCase.reindex();
                }
            } catch (RuntimeException e) {
                log.warn("판매글 검색 색인 복구 재색인 실패, 다음 실행에서 다시 시도", e);
            } finally {
                running.set(false);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        if (reindexExecutor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }
}
