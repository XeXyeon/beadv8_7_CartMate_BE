package com.cartmate.cartmate.boundedContext.productService.payback.out;

import com.cartmate.cartmate.boundedContext.productService.payback.domain.PaybackOrder;

import java.util.Optional;

public interface PaybackOrderQueryRepository {

    // 주문 ID로 비관적 쓰기 잠금을 걸어 읽는다
    Optional<PaybackOrder> findByIdForUpdate(Long orderId);

    Optional<Long> findIndividualGroupPurchaseId(Long orderId);
}
