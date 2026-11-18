package com.cartmate.cartmate.boundedContext.productService.search.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import org.junit.jupiter.api.Test;

class SalesPostSearchConditionTest {

    @Test
    void 공백_검색어는_검색어_없음으로_보고_최신순이다() {
        var condition = new SalesPostSearchCondition("   ", null, null, null, null);

        assertThat(condition.keyword()).isNull();
        assertThat(condition.sort()).isEqualTo(SalesPostSearchSort.LATEST);
    }

    @Test
    void 검색어가_있으면_기본은_관련도순이다() {
        var condition = new SalesPostSearchCondition(" 이어폰 ", null, null, null, null);

        assertThat(condition.keyword()).isEqualTo("이어폰");
        assertThat(condition.sort()).isEqualTo(SalesPostSearchSort.RELEVANCE);
    }

    @Test
    void 검색어_없이_관련도순을_고르면_최신순으로_본다() {
        var condition = new SalesPostSearchCondition(null, null, null, null, SalesPostSearchSort.RELEVANCE);

        assertThat(condition.sort()).isEqualTo(SalesPostSearchSort.LATEST);
    }

    @Test
    void 최소_가격이_최대_가격보다_크면_거절한다() {
        assertThatThrownBy(() -> new SalesPostSearchCondition(null, null, 50_000L, 10_000L, null))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(SalesPostErrorCode.SEARCH_PRICE_RANGE_INVALID.getMsg());
    }

    @Test
    void 음수_가격은_거절한다() {
        assertThatThrownBy(() -> new SalesPostSearchCondition(null, null, -1L, null, null))
                .isInstanceOf(ServiceException.class);
    }
}
