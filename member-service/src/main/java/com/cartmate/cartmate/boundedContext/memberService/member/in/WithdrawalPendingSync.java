package com.cartmate.cartmate.boundedContext.memberService.member.in;

import com.cartmate.cartmate.boundedContext.memberService.member.app.MemberFacade;
import com.cartmate.cartmate.boundedContext.memberService.member.out.WithdrawalPendingStore;
import com.cartmate.cartmate.global.event.OutboxMessageRelayed;
import com.cartmate.cartmate.shared.member.event.MemberWithdrawalRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 탈퇴를 막 신청한 회원의 Redis 표시를 둔다. 신청 전에 받은 토큰(wp 없음)으로 다른 기기에서 하는 변경 요청을
 * 모든 서비스의 WithdrawalPendingFilter가 이 표시로 막는다.
 *
 * <p>탈퇴 신청 트랜잭션이 Outbox에 남긴 MemberWithdrawalRequestedEvent를 릴레이가 이 앱 안으로 전달하면 반영한다 (커밋된 신청만).
 * 이벤트가 아니라 DB의 지금 상태를 다시 읽는다 → 그사이 취소됐으면 표시하지 않고 지운다.
 * Redis 쓰기가 실패하면 예외를 그대로 던져 릴레이가 다시 보낸다 (SuspendedMemberSync와 같은 방식, 같은 앱 안 전달만 처리).
 * 취소할 때 지우기는 이벤트 없이 취소 커밋 직후 한다 (MemberFacade.cancelWithdrawal).
 */
@Component
@RequiredArgsConstructor
public class WithdrawalPendingSync {

    private final MemberFacade memberFacade;
    private final WithdrawalPendingStore withdrawalPendingStore;

    @EventListener(condition = "!#message.fromKafka()")
    public void handle(OutboxMessageRelayed message) {
        if (message.event() instanceof MemberWithdrawalRequestedEvent event) {
            if (memberFacade.isWithdrawalPending(event.memberId())) {
                withdrawalPendingStore.mark(event.memberId());
            } else {
                withdrawalPendingStore.clear(event.memberId());
            }
        }
    }
}
