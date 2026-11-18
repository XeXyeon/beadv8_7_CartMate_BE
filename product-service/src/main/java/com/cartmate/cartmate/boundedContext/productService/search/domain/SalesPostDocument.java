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
        List<String> categoryNames,
        List<Product> products,
        LocalDateTime createdAt
) {

    /** 판매 중인 상품 하나의 카테고리와 가격. 카테고리 · 가격 필터를 같은 상품 기준으로 걸 수 있게 묶어 둔다. */
    public record Product(Long categoryId, Long price) {
    }
}
