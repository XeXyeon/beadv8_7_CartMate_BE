package com.cartmate.cartmate.boundedContext.productService.post.dto;

import java.time.LocalDateTime;

// 판매글 리뷰 목록의 리뷰 하나. 판매자 답글이 없으면 sellerReply는 null
public record SalesPostReviewResponse(
        Long reviewId,
        String writerNickname,
        String productName,
        String optionName,
        int rating,
        String content,
        boolean isEdited,
        boolean isMine,
        LocalDateTime createdAt,
        SellerReply sellerReply
) {

    public record SellerReply(
            String content,
            LocalDateTime repliedAt,
            boolean isEdited
    ) {
    }
}
