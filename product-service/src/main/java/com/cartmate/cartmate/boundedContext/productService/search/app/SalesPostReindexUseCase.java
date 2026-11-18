package com.cartmate.cartmate.boundedContext.productService.search.app;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.search.out.SalesPostIndexAdmin;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.lock.LockErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockingTaskExecutor.TaskResult;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/** 판매글 검색 색인을 새로 만들어 채운 뒤 별칭을 옮긴다. 그동안 검색은 옛 색인으로 계속된다. */
@Slf4j
@Service
public class SalesPostReindexUseCase {

    // 여러 파드가 동시에 재색인하지 않도록 DB(shedlock 표) 락을 잡는다
    private static final String LOCK_NAME = "salesPostSearchReindex";
    // 파드가 재색인 도중 죽어도 이 시간이 지나면 락이 풀린다
    private static final Duration LOCK_AT_MOST = Duration.ofMinutes(30);
    private static final Limit PAGE = Limit.of(500);

    private final LockingTaskExecutor lockExecutor;
    private final SalesPostIndexAdmin indexAdmin;
    private final SalesPostIndexer indexer;
    private final SalesPostRepository salesPostRepository;
    private final SalesPostIndexHealth indexHealth;

    public SalesPostReindexUseCase(LockProvider lockProvider, SalesPostIndexAdmin indexAdmin, SalesPostIndexer indexer,
                                   SalesPostRepository salesPostRepository, SalesPostIndexHealth indexHealth) {
        this.lockExecutor = new DefaultLockingTaskExecutor(lockProvider);
        this.indexAdmin = indexAdmin;
        this.indexer = indexer;
        this.salesPostRepository = salesPostRepository;
        this.indexHealth = indexHealth;
    }

    /** 새 색인 이름을 돌려준다. 이미 다른 곳에서 재색인 중이면 기다리지 않고 실패한다. */
    public String reindex() {
        LockConfiguration lock = new LockConfiguration(Instant.now(), LOCK_NAME, LOCK_AT_MOST, Duration.ZERO);
        try {
            TaskResult<String> result = lockExecutor.executeWithLock(this::reindexLocked, lock);
            if (!result.wasExecuted()) {
                throw new ServiceException(LockErrorCode.LOCK_ACQUISITION_FAILED);
            }
            return result.getResult();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    private String reindexLocked() {
        // 이 시점까지 쌓인 낡음 표시만 이번 재색인이 해결한다
        long staleSnapshot = indexHealth.snapshot();
        // 지난 재색인이 남긴 색인 · 재색인 별칭을 먼저 치운다(쌓이지 않게, 재색인 별칭이 두 색인에 붙지 않게)
        indexAdmin.cleanUpLeftovers();
        Optional<String> oldIndex = indexAdmin.currentIndex();
        String newIndex = indexAdmin.newIndexName();
        try {
            // 만들다 타임아웃이 나도 아래에서 지운다
            indexAdmin.createIndex(newIndex);
            // 채우는 동안 바뀐 판매글은 sync가 옛 색인과 새 색인 양쪽에 쓴다
            indexAdmin.markReindexing(newIndex);
            fill(newIndex);
            indexAdmin.swapAlias(newIndex);
        } catch (RuntimeException e) {
            // 색인을 지우면 그 색인에 붙은 재색인 별칭도 같이 사라진다. 검색 별칭은 옛 색인에 그대로 있다
            try {
                indexAdmin.deleteIndex(newIndex);
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
        oldIndex.filter(old -> !old.equals(newIndex)).ifPresent(indexAdmin::deleteIndex);
        indexHealth.markFixed(staleSnapshot);
        log.info("판매글 검색 재색인 완료: {} (이전 {})", newIndex, oldIndex.orElse("없음"));
        return newIndex;
    }

    private void fill(String index) {
        long afterId = 0L;
        while (true) {
            List<Long> ids = salesPostRepository.findIdsByStatusAfterId(SalesPostSaleStatus.ON_SALE, afterId, PAGE);
            if (ids.isEmpty()) {
                return;
            }
            indexer.bulkIndex(index, ids);
            afterId = ids.getLast();
        }
    }
}
