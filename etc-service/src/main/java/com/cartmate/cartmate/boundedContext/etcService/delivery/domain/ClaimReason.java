package com.cartmate.cartmate.boundedContext.etcService.delivery.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ClaimReason {
    SIZE_MISMATCH("사이즈 불일치", false),
    SIMPLE_CHANGE("단순 변심", false),
    PRODUCT_DEFECT("상품 불량·파손", true),
    WRONG_DELIVERY("오배송·누락", true),
    OTHER("기타", false);

    private final String label;
    // 판매자 탓이면 true. 판매자 신뢰도 기록과 반품 환불의 쿠폰 할인 계산 방식을 가른다.
    private final boolean sellerFault;
}
