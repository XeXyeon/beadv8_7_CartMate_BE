package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductWish;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductWishRepository;
import com.cartmate.cartmate.support.ProductServiceTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 찜 저장 구조: 회원 · 판매글 상품으로 저장, 같은 회원이 같은 판매글 상품을 두 번 찜할 수 없다(유니크 키).
 */
class SalesPostProductWishStructureTest extends ProductServiceTestSupport {

    private static final Long MEMBER_ID = 100L;
    private static final Long OTHER_MEMBER_ID = 200L;
    private static final Long PRODUCT_ID = 9L;
    private static final Long OTHER_PRODUCT_ID = 10L;

    @Autowired
    SalesPostProductWishRepository salesPostProductWishRepository;

    @BeforeEach
    void setUp() {
        salesPostProductWishRepository.deleteAll();
    }

    @Test
    @DisplayName("찜은 회원과 판매글 상품을 저장하고, 찜했는지 확인할 수 있다")
    void savesWish() {
        SalesPostProductWish saved = salesPostProductWishRepository.save(SalesPostProductWish.of(MEMBER_ID, PRODUCT_ID));

        SalesPostProductWish found = salesPostProductWishRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(found.getSalesPostProductId()).isEqualTo(PRODUCT_ID);
        assertThat(found.getCreateDate()).isNotNull();
        assertThat(salesPostProductWishRepository.existsByMemberIdAndSalesPostProductId(MEMBER_ID, PRODUCT_ID)).isTrue();
        assertThat(salesPostProductWishRepository.existsByMemberIdAndSalesPostProductId(MEMBER_ID, OTHER_PRODUCT_ID)).isFalse();
        assertThat(salesPostProductWishRepository.existsByMemberIdAndSalesPostProductId(OTHER_MEMBER_ID, PRODUCT_ID)).isFalse();
    }

    @Test
    @DisplayName("같은 회원이 같은 판매글 상품을 두 번 찜하면 유니크 키에 막힌다")
    void sameMemberSameProductIsRejected() {
        salesPostProductWishRepository.save(SalesPostProductWish.of(MEMBER_ID, PRODUCT_ID));

        assertThatThrownBy(() -> salesPostProductWishRepository.saveAndFlush(SalesPostProductWish.of(MEMBER_ID, PRODUCT_ID)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(salesPostProductWishRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 회원이 같은 판매글 상품을, 같은 회원이 다른 판매글 상품을 찜하는 건 막지 않는다")
    void differentMemberOrProductIsAllowed() {
        salesPostProductWishRepository.save(SalesPostProductWish.of(MEMBER_ID, PRODUCT_ID));
        salesPostProductWishRepository.save(SalesPostProductWish.of(OTHER_MEMBER_ID, PRODUCT_ID));
        salesPostProductWishRepository.save(SalesPostProductWish.of(MEMBER_ID, OTHER_PRODUCT_ID));

        assertThat(salesPostProductWishRepository.count()).isEqualTo(3);
    }
}
