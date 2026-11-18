package com.cartmate.cartmate.shared.member.event;

import com.cartmate.cartmate.global.event.DomainEvent;

import java.time.LocalDateTime;

/**
 * 판매자 등록 시 발행 (Outbox → 토픽 cartmate.member.seller-registered).
 * payment(정산 지급 대상 판매자)와 product(판매글에 보여줄 판매자 정보)가 받아 복제한다.
 *
 * <p>계좌·사업자번호·전화번호는 <b>넣지 않는다</b>. 받는 쪽이 쓰지 않고, outbox_event · Kafka · 다른 서비스 DB에
 * 퍼지면 안 되는 정보다 (SellerRegisteredEventSchemaTest가 지킨다).
 *
 * @param displayName        사업자면 상호명, 개인이면 닉네임
 * @param representativeName 사업자 대표자명. 개인 판매자는 null
 */
public record SellerRegisteredEvent(
        Long sellerId,
        Long memberId,
        String sellerType,
        String displayName,
        String representativeName,
        String status,
        LocalDateTime createDate,
        LocalDateTime modifyDate
) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "Seller";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(sellerId);
    }
}
