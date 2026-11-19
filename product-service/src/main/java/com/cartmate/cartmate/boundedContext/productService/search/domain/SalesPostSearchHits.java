package com.cartmate.cartmate.boundedContext.productService.search.domain;

import java.util.List;

/** 검색 색인이 돌려준 한 페이지의 판매글 id(순서 유지)와 전체 건수. */
public record SalesPostSearchHits(List<Long> postIds, long total) {
}
