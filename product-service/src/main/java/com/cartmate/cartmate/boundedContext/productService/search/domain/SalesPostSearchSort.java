package com.cartmate.cartmate.boundedContext.productService.search.domain;

/** 판매글 검색 정렬. 가격순은 판매글 안 판매 중 상품의 최저가 기준이다. */
public enum SalesPostSearchSort {
    RELEVANCE,
    LATEST,
    PRICE_ASC,
    PRICE_DESC
}
