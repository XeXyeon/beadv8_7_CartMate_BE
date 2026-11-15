package com.cartmate.cartmate.boundedContext.productService.post.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SalesPostSaleStatus {
    ON_SALE("판매 중"),
    PAUSED("판매 중지"),     // 판매자가 직접 중지
    SUSPENDED("운영 중지"),  // 관리자에 의한 판매자 정지
    CLOSED("판매 종료"),
    DELETED("삭제"),
    DELETING("삭제 중"),
    PENDING("등록 중");

    private final String label;

    // 다른 사용자에게 판매글(과 댓글)이 보이는 상태. 운영 중지 · 삭제 · 등록 중 · 삭제 중은 보이지 않는다
    public boolean isPubliclyVisible() {
        return this == ON_SALE || this == PAUSED || this == CLOSED;
    }

    // 새 댓글 · 답글을 쓸 수 있는 상태. 판매 종료는 보이지만 새로 쓸 수는 없다
    public boolean isCommentWritable() {
        return this == ON_SALE || this == PAUSED;
    }
}
