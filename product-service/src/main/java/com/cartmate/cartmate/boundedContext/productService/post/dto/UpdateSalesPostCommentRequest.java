package com.cartmate.cartmate.boundedContext.productService.post.dto;

// 댓글 · 답글 수정 요청. 내용만 바꿀 수 있다 (비밀 여부는 작성할 때 정한 그대로)
public record UpdateSalesPostCommentRequest(String content) {
}
