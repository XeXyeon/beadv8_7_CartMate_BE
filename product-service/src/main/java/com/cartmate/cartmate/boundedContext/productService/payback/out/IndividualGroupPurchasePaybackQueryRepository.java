package com.cartmate.cartmate.boundedContext.productService.payback.out;

import com.cartmate.cartmate.boundedContext.productService.payback.domain.IndividualGroupPurchasePayback;

import java.util.Optional;

public interface IndividualGroupPurchasePaybackQueryRepository {

    // 개인물품 공동구매 ID로 비관적 쓰기 잠금을 걸어 읽는다
    Optional<IndividualGroupPurchasePayback> findByIdForUpdate(Long individualGroupPurchaseId);
}
