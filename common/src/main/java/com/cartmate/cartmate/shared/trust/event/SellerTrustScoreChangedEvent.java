package com.cartmate.cartmate.shared.trust.event;

import com.cartmate.cartmate.global.event.DomainEvent;

import java.time.LocalDateTime;

// 판매자의 신뢰도 점수나 등급이 바뀔 때 발행 (Outbox → 토픽 cartmate.trust.seller-trust-score-changed).
// post가 받아서 판매글에 보여줄 판매자 정보를 복제한다. 늦게 온 이전 점수는 calculatedAt으로 걸러 낸다.
public record SellerTrustScoreChangedEvent(
        Long sellerId,
        Integer score,             // 거래 표본이 부족한 NEW 등급이면 null
        String level,              // TrustLevel 이름 (NEW, LOW, NORMAL, HIGH)
        LocalDateTime calculatedAt
) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "SellerTrust";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(sellerId);
    }
}
