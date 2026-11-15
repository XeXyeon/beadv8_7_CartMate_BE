package com.cartmate.cartmate.boundedContext.productService.post.dto;

// 원댓글 작성 요청
public record CreateSalesPostCommentRequest(String content, boolean isSecret) {
}
