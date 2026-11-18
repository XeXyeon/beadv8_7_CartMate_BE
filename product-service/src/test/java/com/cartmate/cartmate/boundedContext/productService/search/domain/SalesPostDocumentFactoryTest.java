package com.cartmate.cartmate.boundedContext.productService.search.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class SalesPostDocumentFactoryTest {

    private static final Map<Long, String> CATEGORIES = Map.of(1L, "유니폼 / 의류", 2L, "운동 용품");

    @Test
    void 판매_중인_상품만_문서에_담는다() {
        SalesPost post = post(SalesPostSaleStatus.ON_SALE);
        addProduct(post, 1L, "이어폰", 30_000L, SalesPostProductStatus.ON_SALE);
        addProduct(post, 2L, "헤드셋", 10_000L, SalesPostProductStatus.DELETED);

        SalesPostDocument doc = SalesPostDocumentFactory.create(post, CATEGORIES).orElseThrow();

        assertThat(doc.postId()).isEqualTo(100L);
        assertThat(doc.productNames()).containsExactly("이어폰");
        assertThat(doc.prices()).containsExactly(30_000L);
        assertThat(doc.minPrice()).isEqualTo(30_000L);
    }

    @ParameterizedTest
    @EnumSource(value = SalesPostSaleStatus.class, names = "ON_SALE", mode = EnumSource.Mode.EXCLUDE)
    void 판매_중이_아닌_판매글은_문서가_없다(SalesPostSaleStatus status) {
        SalesPost post = post(status);
        addProduct(post, 1L, "이어폰", 30_000L, SalesPostProductStatus.ON_SALE);

        assertThat(SalesPostDocumentFactory.create(post, CATEGORIES)).isEmpty();
    }

    @Test
    void 상품이_모두_삭제된_판매글은_문서가_없다() {
        SalesPost post = post(SalesPostSaleStatus.ON_SALE);
        addProduct(post, 1L, "이어폰", 30_000L, SalesPostProductStatus.DELETED);

        assertThat(SalesPostDocumentFactory.create(post, CATEGORIES)).isEmpty();
    }

    @Test
    void 카테고리_이름은_id로_찾아_중복_없이_담는다() {
        SalesPost post = post(SalesPostSaleStatus.ON_SALE);
        addProduct(post, 1L, "상의", 20_000L, SalesPostProductStatus.ON_SALE, 1L);
        addProduct(post, 2L, "하의", 25_000L, SalesPostProductStatus.ON_SALE, 1L);

        SalesPostDocument doc = SalesPostDocumentFactory.create(post, CATEGORIES).orElseThrow();

        assertThat(doc.categoryIds()).containsExactly(1L);
        assertThat(doc.categoryNames()).containsExactly("유니폼 / 의류");
        assertThat(doc.minPrice()).isEqualTo(20_000L);
    }

    private SalesPost post(SalesPostSaleStatus status) {
        SalesPost post = new SalesPost(7L, "무선 이어폰 팝니다", "새 제품", status, 0L, null, null);
        ReflectionTestUtils.setField(post, "id", 100L);
        return post;
    }

    private void addProduct(SalesPost post, Long id, String name, Long price, SalesPostProductStatus status) {
        addProduct(post, id, name, price, status, 2L);
    }

    private void addProduct(SalesPost post, Long id, String name, Long price, SalesPostProductStatus status, Long categoryId) {
        post.getProductList().add(new SalesPostProduct(id, 7L, post, categoryId, name, name + " 설명", price, status, null, null));
    }
}
