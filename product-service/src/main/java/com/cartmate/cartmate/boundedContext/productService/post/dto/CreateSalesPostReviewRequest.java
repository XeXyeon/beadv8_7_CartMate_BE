package com.cartmate.cartmate.boundedContext.productService.post.dto;

// 리뷰 작성 요청. 판매글 id는 받지 않고 구매 기록의 상품으로 찾는다
public record CreateSalesPostReviewRequest(Long purchaseId, int rating, String content) {
}
