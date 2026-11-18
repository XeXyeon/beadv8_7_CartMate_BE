package com.cartmate.cartmate.shared.member.event;

import com.cartmate.cartmate.global.event.DomainEvent;

import java.time.LocalDateTime;

// 회원 정지·해제 시 발행 (Outbox, 토픽 cartmate.member.member-status-changed).
// 회원 서비스는 릴레이로 받아 정지 회원 Redis 키를 DB 상태대로 맞춘다. product는 판매자 정지 시 판매글 숨김 처리에 사용
public record MemberStatusChangedEvent(
        Long memberId,
        String role,
        String status,              // ACTIVE, SUSPENDED
        LocalDateTime suspendedUntil // 정지일 때만, 영구면 null
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
