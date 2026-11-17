package com.cartmate.cartmate.boundedContext.productService.post.dto;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductWishDisplayStatus;

import java.time.LocalDateTime;

/**
 * 내 찜 목록 한 줄 (찜 하나).
 * 볼 수 없는 상품(운영 중지)은 상품명 · 대표 이미지 · 가격을 비우고, 찜 해제할 수 있게 id만 준다.
 *
 * @param thumbnailS3Key 대표 이미지(활성 이미지 중 순서가 가장 앞선 것)의 s3Key. 이미지가 없으면 null
 */
public record SalesPostProductWishResponse(
        Long salesPostId,
        Long productId,
        String productName,
        String thumbnailS3Key,
        Long basePrice,
        SalesPostProductWishDisplayStatus displayStatus,
        LocalDateTime wishedAt
) {
}
