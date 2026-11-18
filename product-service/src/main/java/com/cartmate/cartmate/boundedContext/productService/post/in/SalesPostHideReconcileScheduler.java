package com.cartmate.cartmate.boundedContext.productService.post.in;

import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostHideUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 판매글 숨김 후처리 대사(reconciliation).
 *
 * <p>숨김 이벤트는 Outbox로 나가 유실·처리 실패는 Outbox · Inbox 재시도가 맡는다. 남는 빈틈은 하나다:
 * 주문 쪽이 숨김을 처리할 때 아직 결제 전이던 주문이 그 직후 결제 완료된 경우. 이걸 잡으려고 최근
 * {@link #WINDOW} 안에 내려간 판매글만 다시 보낸다 (다시 보낼 때마다 Kafka → 주문 Inbox 처리가 생겨 범위를 짧게 둔다).
 *
 * <p>주문 쪽이 결제 완료 시 숨긴 상품을 스스로 확인하게 되면(별도 PR) 이 스케줄러는 지운다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cartmate.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SalesPostHideReconcileScheduler {

    static final String LOCK_NAME = "salesPostHideReconcile";
    private static final long INTERVAL_MILLIS = 10 * 60_000L;
    static final Duration WINDOW = Duration.ofMinutes(30);

    private final SalesPostHideUseCase salesPostHideUseCase;

    @Scheduled(fixedDelay = INTERVAL_MILLIS, initialDelay = INTERVAL_MILLIS)
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    public void reconcile() {
        int count = salesPostHideUseCase.republishRecentlyHidden(LocalDateTime.now().minus(WINDOW));
        if (count > 0) {
            log.info("판매글 숨김 대사: {}건 재발행", count);
        }
    }
}
