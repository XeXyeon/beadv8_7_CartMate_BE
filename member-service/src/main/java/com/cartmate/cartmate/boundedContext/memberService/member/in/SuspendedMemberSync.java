package com.cartmate.cartmate.boundedContext.memberService.member.in;

import com.cartmate.cartmate.boundedContext.memberService.member.app.MemberFacade;
import com.cartmate.cartmate.boundedContext.memberService.member.out.SuspendedMemberStore;
import com.cartmate.cartmate.global.event.OutboxMessageRelayed;
import com.cartmate.cartmate.shared.member.event.MemberStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 정지 회원 Redis 키를 DB와 맞춘다. 모든 서비스의 JwtAuthenticationFilter가 이 키로 정지 회원을 막는다.
 *
 * <p>정지·해제는 같은 트랜잭션에서 Outbox에 MemberStatusChangedEvent를 남기고, 릴레이가 이 앱 안으로 전달하면 여기서 반영한다.
 * 이벤트 값이 아니라 DB의 지금 상태를 다시 읽어 SET/DEL 하므로 순서가 바뀌거나 여러 번 와도 결과가 같다.
 * Redis 쓰기가 실패하면 예외를 그대로 던진다 → 릴레이가 실패로 기록하고 간격을 늘려 다시 보낸다 (스케줄러를 따로 두지 않음).
 *
 * <p>같은 앱 안 전달(fromKafka=false)만 받는다. Kafka를 켜면 릴레이가 같은 앱 안과 Kafka 양쪽으로 보내지만,
 * 회원 서비스는 member 모듈을 Kafka로 다시 받는 구독(RelaySubscription)이 없어 Kafka 쪽 사본은 여기로 오지 않는다.
 * 나중에 구독을 더해도 조건 때문에 한 번만 처리한다. Kafka 쪽 재시도(1분 뒤 DLT)가 아니라 릴레이 재시도를 쓰려고 이렇게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuspendedMemberSync {

    private final MemberFacade memberFacade;
    private final SuspendedMemberStore suspendedMemberStore;

    @EventListener(condition = "!#message.fromKafka()")
    public void handle(OutboxMessageRelayed message) {
        if (message.event() instanceof MemberStatusChangedEvent event) {
            sync(event.memberId());
        }
    }

    void sync(Long memberId) {
        if (memberFacade.isSuspended(memberId)) {
            suspendedMemberStore.markSuspended(memberId);
        } else {
            suspendedMemberStore.clear(memberId);
        }
    }

    // 서버 시작 시 한 번: DB의 정지 회원을 모두 표시하고, 정지가 아닌데 남은 키는 지운다 (릴레이가 끝내 실패한 경우 등).
    // Redis가 죽어 있어도 서버는 뜬다 (ERROR만 남기고, 이후 정지·해제는 릴레이 재시도로 맞춰진다)
    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        try {
            List<Long> suspended = memberFacade.findSuspendedMemberIds();
            suspended.forEach(suspendedMemberStore::markSuspended);

            Set<Long> suspendedSet = new HashSet<>(suspended);
            int cleared = 0;
            for (Long memberId : suspendedMemberStore.findMarkedMemberIds()) {
                // 목록을 읽은 뒤 막 정지된 회원일 수 있어 지우기 전에 한 번 더 확인한다
                if (!suspendedSet.contains(memberId) && !memberFacade.isSuspended(memberId)) {
                    suspendedMemberStore.clear(memberId);
                    cleared++;
                }
            }
            log.info("정지 회원 Redis 키 맞춤: 정지 {}명, 남은 키 정리 {}건", suspended.size(), cleared);
        } catch (RuntimeException e) {
            log.atError().addKeyValue("event", "member.suspended.startup-sync-failed")
                    .setCause(e)
                    .log("서버 시작 시 정지 회원 Redis 키 맞춤 실패 → 다른 서비스에서 정지 회원이 즉시 막히지 않을 수 있다");
        }
    }
}
