package com.cartmate.cartmate.boundedContext.memberService.member.in;

import com.cartmate.cartmate.boundedContext.memberService.member.app.MemberFacade;
import com.cartmate.cartmate.boundedContext.memberService.member.out.WithdrawalPendingStore;
import com.cartmate.cartmate.global.event.EventEnvelope;
import com.cartmate.cartmate.global.event.OutboxMessageRelayed;
import com.cartmate.cartmate.shared.member.event.MemberWithdrawalRequestedEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 탈퇴 대기 Redis 표시: 이벤트가 아니라 DB의 지금 상태로 (릴레이 전에 취소됐으면 표시하지 않는다)
class WithdrawalPendingSyncTest {

    private static final long MEMBER = 1L;

    private final MemberFacade memberFacade = mock(MemberFacade.class);
    private final WithdrawalPendingStore store = mock(WithdrawalPendingStore.class);
    private final WithdrawalPendingSync sync = new WithdrawalPendingSync(memberFacade, store);

    private static OutboxMessageRelayed relayed() {
        return new OutboxMessageRelayed(mock(EventEnvelope.class),
                new MemberWithdrawalRequestedEvent(MEMBER, null, LocalDateTime.now()));
    }

    @Test
    void 아직_탈퇴_대기면_표시한다() {
        given(memberFacade.isWithdrawalPending(MEMBER)).willReturn(true);

        sync.handle(relayed());

        verify(store).mark(MEMBER);
    }

    @Test
    void 릴레이_전에_취소됐으면_표시하지_않고_지운다() {
        given(memberFacade.isWithdrawalPending(MEMBER)).willReturn(false);

        sync.handle(relayed());

        verify(store, never()).mark(any());
        verify(store).clear(MEMBER);
    }
}
