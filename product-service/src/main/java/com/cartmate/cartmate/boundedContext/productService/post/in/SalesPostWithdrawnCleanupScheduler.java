package com.cartmate.cartmate.boundedContext.productService.post.in;

import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostFacade;
import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostWithdrawnCleanupUseCase;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 탈퇴 회원 댓글 · 리뷰 정리. 매일 0시에 한 번 돈다.
 *
 * <p>회원 서버에서 마지막 성공 시각 이후 탈퇴 완료된 회원 ID를 받아, 그 회원들의 댓글 · 리뷰를 삭제 처리한다.
 * 실패하면(회원 서버 응답 없음 등) 마지막 성공 시각이 그대로라 다음 날 그 기간까지 함께 처리한다.
 * 탈퇴가 완료된 뒤 다음 실행까지(최대 하루) 글이 보이는 것은 감수한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cartmate.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SalesPostWithdrawnCleanupScheduler {

    static final String LOCK_NAME = "post.cleanupWithdrawnMembers";

    private final SalesPostFacade salesPostFacade;

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Observed(name = "batch.post.withdrawn-member-cleanup") // 실행 1회 = span 하나
    public void cleanup() {
        try {
            SalesPostWithdrawnCleanupUseCase.Result result = salesPostFacade.cleanupWithdrawnMembers(LocalDateTime.now());
            log.info("탈퇴 회원 댓글 · 리뷰 정리: 기간 {} ~ {}, 탈퇴 회원 {}명, 댓글 {}개 · 리뷰 {}개 삭제",
                    result.from(), result.to(), result.withdrawnMembers(),
                    result.deletedComments(), result.deletedReviews());
        } catch (RuntimeException e) {
            log.error("탈퇴 회원 댓글 · 리뷰 정리 실패 — 마지막 성공 시각은 그대로, 다음 실행에서 이 기간까지 다시 처리", e);
        }
    }
}
