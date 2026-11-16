package com.cartmate.cartmate.shared.order;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OrderCancelReason {
    BUYER_CANCELED("구매자 취소"),
    PAYMENT_EXPIRED("결제 기한 만료"),
    OUT_OF_STOCK("재고 부족"),
    SALES_STOPPED("판매 중지"),              // 판매글 판매 중지 · 숨김 · 삭제
    GROUP_PURCHASE_CLOSED("공동구매 마감"),  // 개인물품 공동구매가 닫힘(결제 마감 = 판매 마감)
    SELLER_CANCELED("판매자 사유 취소"),    // 판매자 사유 취소로 모든 상품이 취소됨
    MULTIPLE_REASONS("여러 사유");          // 상품마다 취소 사유가 달라 한 사유로 말할 수 없음

    private final String label;
}
