package com.cartmate.cartmate.boundedContext.memberService.member.in;

import com.cartmate.cartmate.boundedContext.memberService.member.app.MemberFacade;
import com.cartmate.cartmate.boundedContext.memberService.member.out.SuspendedMemberStore;
import com.cartmate.cartmate.global.event.EventEnvelope;
import com.cartmate.cartmate.global.event.OutboxMessageRelayed;
import com.cartmate.cartmate.shared.member.event.MemberStatusChangedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// 정지 회원 Redis 키: 이벤트 값이 아니라 DB의 지금 상태로 SET/DEL, 실패는 던져서 릴레이가 다시 시도
class SuspendedMemberSyncTest {

    private static final long MEMBER = 1L;

    private final MemberFacade memberFacade = mock(MemberFacade.class);
    private final SuspendedMemberStore store = mock(SuspendedMemberStore.class);
    private final SuspendedMemberSync sync = new SuspendedMemberSync(memberFacade, store);

    private static OutboxMessageRelayed relayed(String status) {
        return new OutboxMessageRelayed(mock(EventEnvelope.class),
                new MemberStatusChangedEvent(MEMBER, "BUYER", status, null));
    }

    @Test
    void DB가_정지면_이벤트가_해제여도_키를_남긴다() {
        given(memberFacade.isSuspended(MEMBER)).willReturn(true);

        sync.handle(relayed("ACTIVE")); // 늦게 도착한 해제 이벤트

        verify(store).markSuspended(MEMBER);
        verify(store, never()).clear(any());
    }

    @Test
    void DB가_정지가_아니면_이벤트가_정지여도_키를_지운다() {
        given(memberFacade.isSuspended(MEMBER)).willReturn(false);

        sync.handle(relayed("SUSPENDED")); // 이미 해제된 뒤 다시 전달된 정지 이벤트

        verify(store).clear(MEMBER);
        verify(store, never()).markSuspended(any());
    }

    @Test
    void Redis_쓰기가_실패하면_예외를_던져_릴레이가_다시_시도하게_한다() {
        given(memberFacade.isSuspended(MEMBER)).willReturn(true);
        willThrow(new RedisConnectionFailureException("down")).given(store).markSuspended(MEMBER);

        assertThatThrownBy(() -> sync.handle(relayed("SUSPENDED")))
                .isInstanceOf(RedisConnectionFailureException.class);
    }

    @Test
    void 서버_시작_때_정지_회원은_표시하고_정지가_아닌_남은_키는_지운다() {
        given(memberFacade.findSuspendedMemberIds()).willReturn(List.of(1L, 2L));
        given(store.findMarkedMemberIds()).willReturn(List.of(1L, 2L, 3L));
        given(memberFacade.isSuspended(3L)).willReturn(false);

        sync.loadOnStartup();

        verify(store).markSuspended(1L);
        verify(store).markSuspended(2L);
        verify(store).clear(3L);
        verify(store, never()).clear(1L);
    }

    // Kafka를 켜면 릴레이가 같은 앱 안(fromKafka=false)과 Kafka 양쪽으로 보낸다. 같은 앱 안 전달만 처리해 한 번만 반영한다
    @Test
    void Kafka에서_다시_온_사본은_처리하지_않고_같은_앱_안_전달만_한_번_처리한다() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SuspendedMemberSync.class, () -> sync);
            context.refresh();
            given(memberFacade.isSuspended(MEMBER)).willReturn(true);
            OutboxMessageRelayed inProcess = relayed("SUSPENDED");

            context.publishEvent(new OutboxMessageRelayed(inProcess.envelope(), inProcess.event(), true));
            verifyNoInteractions(store);

            context.publishEvent(inProcess);
            verify(store, times(1)).markSuspended(MEMBER);
        }
    }
}
