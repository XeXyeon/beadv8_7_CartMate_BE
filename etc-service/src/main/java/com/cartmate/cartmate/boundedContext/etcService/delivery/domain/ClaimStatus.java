package com.cartmate.cartmate.boundedContext.etcService.delivery.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.EnumSet;
import java.util.Set;

@Getter
@RequiredArgsConstructor
public enum ClaimStatus {
    VERIFYING("확인 중"),       // 구매자 사정 반품의 접수 검사를 주문 서버에 맡긴 상태. 판매자에게 보이지 않는다.
    NOT_ACCEPTED("접수 불가"),  // 접수 검사에서 거절됨(쿠폰 최소 금액 등). 끝난 상태다.
    REQUESTED("접수"),          // 접수 (판매자 확인 전)
    APPROVED("승인"),           // 판매자 승인
    REJECTED("거절"),           // 판매자 거절
    COMPLETED("처리 완료");

    private final String label;

    // 처리 중인 클레임. 같은 상품의 새 접수와 구매확정, 회원 탈퇴를 막는다.
    public static Set<ClaimStatus> inProgress() {
        return EnumSet.of(VERIFYING, REQUESTED, APPROVED);
    }
}
