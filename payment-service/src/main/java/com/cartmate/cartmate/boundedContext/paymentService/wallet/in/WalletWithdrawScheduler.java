package com.cartmate.cartmate.boundedContext.paymentService.wallet.in;

import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.WalletFacade;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.WalletWithdrawTransferProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.function.Consumer;

/**
 * 예치금 출금 자동 이체 보정 (manual 모드면 만들지 않는다 — 관리자가 처리).
 *
 * <ul>
 *   <li>1분 넘게 PENDING: 신청 직후 이체를 시작하지 못한 건 (서버 재시작 등) → 이체</li>
 *   <li>1분 넘게 PROCESSING: 결과를 모르는 건 → 결과 조회로 확정, 30분 넘게 모르면 관리자 확인으로</li>
 * </ul>
 * 서버가 여러 대면 ShedLock으로 한 대만. 같은 건을 신청 직후 스레드와 동시에 잡아도 조건부 UPDATE라 이체는 한 번.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${custom.wallet.withdraw.transfer-mode:fake}' != 'manual'")
@RequiredArgsConstructor
public class WalletWithdrawScheduler {

    static final String LOCK_NAME = "wallet.withdrawTransfer";
    static final String ZONE = "Asia/Seoul";
    private static final int BATCH = 50;

    private final WalletFacade walletFacade;
    private final WalletWithdrawTransferProcessor transferProcessor;

    @Scheduled(cron = "${custom.wallet.withdraw.retry-cron:30 * * * * *}", zone = ZONE)
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void run() {
        // 비교 대상(created_at·processing_started_at)이 서버 시간대로 저장되므로 여기도 서버 시간대 (cron만 한국 시간)
        LocalDateTime before = LocalDateTime.now().minusMinutes(1);
        each(walletFacade.findStalePendingWithdrawIds(before, BATCH), transferProcessor::transfer, "transfer");
        each(walletFacade.findUnconfirmedWithdrawIds(before, BATCH), transferProcessor::confirm, "confirm");
    }

    // 한 건이 실패해도 나머지는 처리한다
    private static void each(Iterable<Long> ids, Consumer<Long> action, String step) {
        for (Long id : ids) {
            try {
                action.accept(id);
            } catch (RuntimeException e) {
                log.atWarn().addKeyValue("event", "wallet.withdraw.scheduler").addKeyValue("step", step)
                        .addKeyValue("withdrawOrderId", id).setCause(e)
                        .log("출금 보정 실패 → 다음 실행에서 다시: step={}, withdrawOrderId={}", step, id);
            }
        }
    }
}
