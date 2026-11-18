package com.cartmate.cartmate.boundedContext.productService.search.domain;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import java.util.List;

/** 판매글 검색 조건. 만들 때 검색어를 다듬고 정렬 기본값을 정하며, 잘못된 가격 범위는 거절한다. */
public record SalesPostSearchCondition(
        String keyword,
        List<Long> categoryIds,
        Long minPrice,
        Long maxPrice,
        SalesPostSearchSort sort
) {

    public SalesPostSearchCondition {
        keyword = keyword == null || keyword.isBlank() ? null : keyword.strip();
        categoryIds = categoryIds == null ? List.of() : List.copyOf(categoryIds);
        if (isNegative(minPrice) || isNegative(maxPrice)
                || (minPrice != null && maxPrice != null && minPrice > maxPrice)) {
            throw new ServiceException(SalesPostErrorCode.SEARCH_PRICE_RANGE_INVALID);
        }
        // 검색어가 없으면 관련도를 매길 수 없으니 최신순으로 본다
        if (sort == null || (sort == SalesPostSearchSort.RELEVANCE && keyword == null)) {
            sort = keyword == null ? SalesPostSearchSort.LATEST : SalesPostSearchSort.RELEVANCE;
        }
    }

    public boolean hasKeyword() {
        return keyword != null;
    }

    private static boolean isNegative(Long price) {
        return price != null && price < 0;
    }
}
