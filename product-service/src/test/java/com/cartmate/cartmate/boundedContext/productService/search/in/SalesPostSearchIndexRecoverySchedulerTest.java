package com.cartmate.cartmate.boundedContext.productService.search.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.cartmate.cartmate.boundedContext.productService.search.app.SalesPostIndexHealth;
import com.cartmate.cartmate.boundedContext.productService.search.app.SalesPostReindexUseCase;
import com.cartmate.cartmate.boundedContext.productService.search.out.SalesPostIndexAdmin;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SalesPostSearchIndexRecoverySchedulerTest {

    private final SalesPostIndexHealth indexHealth = new SalesPostIndexHealth();   // 막 뜬 앱처럼 낡음으로 시작
    private final SalesPostIndexAdmin indexAdmin = mock(SalesPostIndexAdmin.class);
    private final SalesPostReindexUseCase reindexUseCase = mock(SalesPostReindexUseCase.class);
    private final SalesPostSearchIndexRecoveryScheduler scheduler =
            new SalesPostSearchIndexRecoveryScheduler(indexHealth, indexAdmin, reindexUseCase, Runnable::run);

    @Test
    void 낡았고_ES가_응답하면_재색인한다() {
        given(indexAdmin.isAvailable()).willReturn(true);

        scheduler.reindexIfStale();

        verify(reindexUseCase).reindex();
    }

    @Test
    void ES가_응답하지_않으면_재색인하지_않는다() {
        given(indexAdmin.isAvailable()).willReturn(false);

        scheduler.reindexIfStale();

        verify(reindexUseCase, never()).reindex();
    }

    @Test
    void 낡지_않았으면_ES를_확인하지도_않는다() {
        indexHealth.markFixed(indexHealth.snapshot());

        scheduler.reindexIfStale();

        verifyNoInteractions(indexAdmin, reindexUseCase);
    }

    @Test
    void 재색인은_스케줄러_스레드가_아니라_전용_스레드로_넘긴다() {
        List<Runnable> handedOff = new ArrayList<>();
        var handingOff = new SalesPostSearchIndexRecoveryScheduler(indexHealth, indexAdmin, reindexUseCase, handedOff::add);
        given(indexAdmin.isAvailable()).willReturn(true);

        handingOff.reindexIfStale();

        // 스케줄러 스레드는 넘기기만 하고 바로 돌아온다(outbox 릴레이 등 다른 예약 작업이 같은 스레드를 쓴다)
        assertThat(handedOff).hasSize(1);
        verifyNoInteractions(indexAdmin, reindexUseCase);

        handedOff.getFirst().run();
        verify(reindexUseCase).reindex();
    }

    @Test
    void 재색인이_도는_중이면_또_넘기지_않는다() {
        List<Runnable> handedOff = new ArrayList<>();
        var handingOff = new SalesPostSearchIndexRecoveryScheduler(indexHealth, indexAdmin, reindexUseCase, handedOff::add);

        handingOff.reindexIfStale();
        handingOff.reindexIfStale();

        assertThat(handedOff).hasSize(1);
    }

    @Test
    void 재색인이_실패해도_예외를_던지지_않는다() {
        given(indexAdmin.isAvailable()).willReturn(true);
        willThrow(new IllegalStateException("bulk 실패")).given(reindexUseCase).reindex();

        assertThatCode(scheduler::reindexIfStale).doesNotThrowAnyException();
    }
}
