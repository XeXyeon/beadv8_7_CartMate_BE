package com.cartmate.cartmate.boundedContext.productService.post.dto;

import java.time.LocalDateTime;
import java.util.List;

// 댓글 목록의 원댓글 하나와 그 답글들(오래된 순).
// 삭제 · 비밀 글의 표시 방식은 SalesPostReplyResponse와 같다
public record SalesPostCommentResponse(
        Long commentId,
        String writerNickname,
        String content,
        boolean isSecret,
        boolean isEdited,
        boolean isDeleted,
        boolean isMine,
        LocalDateTime createdAt,
        List<SalesPostReplyResponse> replies
) {
}
