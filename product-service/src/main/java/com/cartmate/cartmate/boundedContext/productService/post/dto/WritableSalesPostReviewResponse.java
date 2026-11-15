package com.cartmate.cartmate.boundedContext.productService.post.dto;

import java.time.LocalDateTime;

// 리뷰를 쓸 수 있는 상품 하나 (구매확정됐고, 기한이 남았고, 아직 리뷰를 안 쓴 구매 기록)
public record WritableSalesPostReviewResponse(
        Long purchaseId,
        Long salesPostId,
        String productName,
        String optionName,
        LocalDateTime confirmedAt,
        LocalDateTime writableUntil
) {
}
