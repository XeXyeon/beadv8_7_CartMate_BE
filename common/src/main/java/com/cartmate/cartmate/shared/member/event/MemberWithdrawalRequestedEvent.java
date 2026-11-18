package com.cartmate.cartmate.shared.member.event;

import com.cartmate.cartmate.global.event.DomainEvent;

import java.time.LocalDateTime;

/**
 * 회원이 탈퇴를 신청했다 (탈퇴 대기 시작).
 *
 * <p>Outbox(→ 토픽 cartmate.member.member-withdrawal-requested)로 발행하고 product가 받는다.
 * 탈퇴 대기 회원 차단은 이벤트가 아니라 Access Token의 탈퇴 대기 표시(wp)가 맡는다.
 *
 * @param sellerId 판매자면 판매자 ID, 아니면 null. post가 받아 판매글을 전부 판매 중지한다.
 */
public record MemberWithdrawalRequestedEvent(
        Long memberId,
        Long sellerId,
        LocalDateTime requestedAt
) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "Member";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(memberId);
    }
}
