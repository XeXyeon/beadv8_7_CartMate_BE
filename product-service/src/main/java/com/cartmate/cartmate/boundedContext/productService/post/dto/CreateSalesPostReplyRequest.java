package com.cartmate.cartmate.boundedContext.productService.post.dto;

// 답글 작성 요청. 비밀 여부는 원댓글을 따르므로 받지 않는다
public record CreateSalesPostReplyRequest(String content) {
}
