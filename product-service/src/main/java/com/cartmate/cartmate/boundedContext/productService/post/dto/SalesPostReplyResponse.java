package com.cartmate.cartmate.boundedContext.productService.post.dto;

import java.time.LocalDateTime;

// 댓글 목록의 답글 하나.
// 삭제된 글은 writerNickname이 null, content가 "삭제된 댓글입니다."이고,
// 볼 권한이 없는 비밀 글은 닉네임은 그대로, content가 "비밀 댓글입니다."다
public record SalesPostReplyResponse(
        Long commentId,
        String writerNickname,
        String content,
        boolean isSecret,
        boolean isSellerReply,
        boolean isEdited,
        boolean isDeleted,
        boolean isMine,
        LocalDateTime createdAt
) {
}
