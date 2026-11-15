package com.cartmate.cartmate.boundedContext.productService.post.dto;

// 리뷰 수정 요청 (별점 · 내용)
public record UpdateSalesPostReviewRequest(int rating, String content) {
}
