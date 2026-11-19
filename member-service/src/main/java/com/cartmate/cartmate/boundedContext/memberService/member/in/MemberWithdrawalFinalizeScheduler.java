package com.cartmate.cartmate.boundedContext.memberService.member.in;

import com.cartmate.cartmate.boundedContext.memberService.member.app.MemberFacade;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 회원 탈퇴 확정. 매일 04:00에 유예 기간이 끝난 탈퇴 대기 회원을 확정한다.
 *
 * <p>product의 탈퇴 회원 정리(매일 0시, 확정 시각으로 받아 감)와 시간이 겹치지 않게 새벽 4시에 돈다.
 * 서버가 여러 대면 ShedLock으로 한 대만 돈다. 회원마다 다른 서비스 HTTP 재검사가 있어 넉넉히 30분까지 잡는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberWithdrawalFinalizeScheduler {

    static final String LOCK_NAME = "member.finalizeWithdrawals";

    private final MemberFacade memberFacade;

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Observed(name = "batch.member.withdrawal-finalize") // 실행 1회 = span 하나
    public void finalizeWithdrawals() {
        MemberFacade.WithdrawalFinalizeResult result = memberFacade.finalizeDueWithdrawals(LocalDateTime.now());
        if (result.finalized() + result.blocked() + result.failed() > 0) {
            log.info("[회원 탈퇴 확정] 확정 {}명, 보류(진행 중 거래·예치금) {}명, 실패 {}명",
                    result.finalized(), result.blocked(), result.failed());
        }
    }
}
