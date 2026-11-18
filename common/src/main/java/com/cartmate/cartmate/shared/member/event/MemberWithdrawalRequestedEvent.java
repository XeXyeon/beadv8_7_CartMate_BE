package com.cartmate.cartmate.shared.member.event;

import com.cartmate.cartmate.global.event.DomainEvent;

import java.time.LocalDateTime;

/**
 * 회원이 탈퇴를 신청했다 (탈퇴 대기 시작).
 *
 * <p>회원 안(탈퇴 대기 목록)은 Spring 이벤트로, 다른 서비스(product)는 Outbox(→ 토픽
 * cartmate.member.member-withdrawal-requested)로 받는다.
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
