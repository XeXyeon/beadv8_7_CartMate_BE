package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductWish;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectSalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectSalesPostRequest;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductOptionRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductWishRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.support.ProductServiceTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willReturn;

/**
 * 판매글 상세의 찜 표시: 로그인한 회원이 찜한 판매글 상품만 wished = true.
 * 비로그인 · 목록 조회는 모두 false.
 */
class SalesPostProductWishDetailTest extends ProductServiceTestSupport {

    private static final Long SELLER_ID = 7L;
    private static final Long MEMBER_ID = 100L;
    private static final Long OTHER_MEMBER_ID = 200L;
    private static final AtomicLong PRODUCT_SEQ = new AtomicLong(9800);

    @Autowired
    SalesPostFacade salesPostFacade;
    @Autowired
    SalesPostProductWishRepository salesPostProductWishRepository;
    @Autowired
    SalesPostRepository salesPostRepository;
    @Autowired
    SalesPostProductRepository salesPostProductRepository;
    @Autowired
    SalesPostProductImageRepository salesPostProductImageRepository;
    @Autowired
    SalesPostProductOptionRepository salesPostProductOptionRepository;

    Long postId;
    Long wishedProduct;
    Long otherProduct;

    @BeforeEach
    void setUp() {
        salesPostProductWishRepository.deleteAll();
        salesPostProductImageRepository.deleteAll();
        salesPostProductOptionRepository.deleteAll();
        salesPostProductRepository.deleteAll();
        salesPostRepository.deleteAll();
        // 선택 목록 · 재고는 상품 서버 호출이라 빈 값으로 정한다 (실제 호출은 SalesPostDetailFlowTest)
        // 재고 로더는 @Cacheable 프록시라 스파이로 정하기 어렵다. 그 아래의 상품 API 응답을 빈 목록으로 정한다
        willReturn(List.of()).given(salesPostApiClient).getProduct(anyLong());

        postId = salesPostRepository.save(new SalesPost(SELLER_ID, "판매글", "내용", SalesPostSaleStatus.ON_SALE, 0L,
                SalesPostUpdateStatus.NEW, LocalDateTime.now())).getId();
        wishedProduct = saveProduct(postId);
        otherProduct = saveProduct(postId);
    }

    @Test
    @DisplayName("상세: 로그인한 회원이 찜한 판매글 상품만 wished = true")
    void marksOnlyMyWishedProducts() {
        wish(MEMBER_ID, wishedProduct);

        assertThat(detailProducts(MEMBER_ID))
                .extracting(SelectSalesPostProduct::getId, SelectSalesPostProduct::isWished)
                .containsExactlyInAnyOrder(tuple(wishedProduct, true), tuple(otherProduct, false));
    }

    @Test
    @DisplayName("상세: 다른 회원의 찜은 내 찜 표시에 영향을 주지 않는다")
    void otherMembersWishDoesNotCount() {
        wish(OTHER_MEMBER_ID, wishedProduct);

        assertThat(detailProducts(MEMBER_ID)).extracting(SelectSalesPostProduct::isWished).containsOnly(false);
    }

    @Test
    @DisplayName("상세: 로그인하지 않으면 모두 wished = false")
    void anonymousSeesNoWish() {
        wish(MEMBER_ID, wishedProduct);

        assertThat(detailProducts(null)).extracting(SelectSalesPostProduct::isWished).containsOnly(false);
    }

    @Test
    @DisplayName("목록: 찜 여부를 붙이지 않아 모두 wished = false")
    void listHasNoWish() {
        wish(MEMBER_ID, wishedProduct);

        List<SelectSalesPostProduct> products = salesPostFacade
                .selectSalesPostAll(new SelectSalesPostRequest(), PageRequest.of(0, 10))
                .getData().content().getFirst().getProductList();

        assertThat(products).extracting(SelectSalesPostProduct::isWished).containsOnly(false);
    }

    private List<SelectSalesPostProduct> detailProducts(Long memberId) {
        return salesPostFacade.selectSalesPostDetail(postId, memberId).getData().getProductList();
    }

    private void wish(Long memberId, Long productId) {
        salesPostProductWishRepository.save(SalesPostProductWish.of(memberId, productId));
    }

    // 판매글의 상품 복제본 (id = 상품 id)
    private Long saveProduct(Long postId) {
        SalesPost post = salesPostRepository.findById(postId).orElseThrow();
        Long id = PRODUCT_SEQ.incrementAndGet();
        salesPostProductRepository.save(new SalesPostProduct(id, SELLER_ID, post, 1L, "반팔 티셔츠", "설명", 10000L,
                SalesPostProductStatus.ON_SALE, LocalDateTime.now(), LocalDateTime.now()));
        return id;
    }
}
