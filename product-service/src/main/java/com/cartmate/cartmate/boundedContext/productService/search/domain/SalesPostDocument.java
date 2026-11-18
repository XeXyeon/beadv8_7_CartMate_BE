package com.cartmate.cartmate.boundedContext.productService.search.domain;

import java.time.LocalDateTime;
import java.util.List;

/** 판매글 검색 색인에 넣는 문서 한 건. 판매 중인 상품만 담는다. */
public record SalesPostDocument(
        Long postId,
        Long sellerId,
        String title,
        String content,
        List<String> productNames,
        List<String> productDescriptions,
        List<Long> categoryIds,
        List<String> categoryNames,
        List<Long> prices,
        Long minPrice,
        LocalDateTime createdAt
) {
}
