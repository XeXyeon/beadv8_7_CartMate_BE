package com.cartmate.cartmate.shared.order;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OrderType {
    PERSONAL("일반 주문"),  // 장바구니로 만든 개인 주문
    INDIVIDUAL_GROUP("개인물품 공동구매 주문"),
    // 공동물품 공동구매 주문(SharedGroupOrder). 주문 테이블(Order)이 아니라 별도 테이블이라 id가 위 두 종류와 겹칠 수 있다.
    // 재고 선점 기록(product-service)에서 공동 주문을 구분하는 데 쓴다
    SHARED_GROUP("공동물품 공동구매 주문");

    private final String label;
}
