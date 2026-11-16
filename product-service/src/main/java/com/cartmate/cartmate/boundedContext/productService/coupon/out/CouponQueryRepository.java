package com.cartmate.cartmate.boundedContext.productService.coupon.out;

public interface CouponQueryRepository {

    // 발급 수를 DB에서 하나 올린다. 총 수량이 다 찼으면 0 → 소진.
    // 여러 명이 동시에 받아도 수량을 넘지 않는다 (읽고 더하는 대신 조건부 UPDATE 한 번)
    // UPDATE 뒤 영속성 컨텍스트를 비워서, 이미 불러온 Coupon의 옛 issuedCount를 다시 쓰거나 덮어쓰지 않게 한다
    int increaseIssuedCount(Long id);
}
