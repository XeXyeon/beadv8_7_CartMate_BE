package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductOptionRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductWishRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.global.exception.ErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.support.ProductServiceTestSupport;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 판매글 상품 찜하기 · 찜 해제.
 * 찜하기는 판매글이 판매 중 · 판매 중지이고 판매글 상품이 삭제되지 않았을 때만 되고, 찜 해제는 언제든 된다.
 * 둘 다 같은 요청을 다시 보내도 결과가 같다.
 */
class SalesPostProductWishTest extends ProductServiceTestSupport {

    private static final Long SELLER_ID = 7L;
    private static final Long MEMBER_ID = 100L;
    private static final AtomicLong PRODUCT_SEQ = new AtomicLong(9600);

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
    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        salesPostProductWishRepository.deleteAll();
        salesPostProductImageRepository.deleteAll();
        salesPostProductOptionRepository.deleteAll();
        salesPostProductRepository.deleteAll();
        salesPostRepository.deleteAll();
    }

    // ===== 찜하기 =====

    @ParameterizedTest
    @EnumSource(value = SalesPostSaleStatus.class, names = {"ON_SALE", "PAUSED"})
    @DisplayName("판매 중 · 판매 중지 판매글의 상품은 찜할 수 있다")
    void wishesProductOfWishablePost(SalesPostSaleStatus status) {
        Long productId = saveProduct(savePost(status), SalesPostProductStatus.ON_SALE);

        salesPostFacade.wishProduct(MEMBER_ID, productId);

        assertThat(salesPostProductWishRepository.existsByMemberIdAndSalesPostProductId(MEMBER_ID, productId)).isTrue();
    }

    @Test
    @DisplayName("판매자도 자기 판매글의 상품을 찜할 수 있다")
    void sellerCanWishOwnProduct() {
        Long productId = saveProduct(savePost(SalesPostSaleStatus.ON_SALE), SalesPostProductStatus.ON_SALE);

        salesPostFacade.wishProduct(SELLER_ID, productId);

        assertThat(salesPostProductWishRepository.existsByMemberIdAndSalesPostProductId(SELLER_ID, productId)).isTrue();
    }

    @Test
    @DisplayName("이미 찜한 상품을 다시 찜해도 성공하고 찜은 한 줄만 남는다")
    void wishTwiceKeepsOneRow() {
        Long productId = saveProduct(savePost(SalesPostSaleStatus.ON_SALE), SalesPostProductStatus.ON_SALE);

        salesPostFacade.wishProduct(MEMBER_ID, productId);
        salesPostFacade.wishProduct(MEMBER_ID, productId);

        assertThat(salesPostProductWishRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 찜한 상품은 판매글이 판매 종료돼도 다시 찜하기를 보내면 성공한다")
    void wishAgainAfterClosedSucceeds() {
        Long postId = savePost(SalesPostSaleStatus.ON_SALE);
        Long productId = saveProduct(postId, SalesPostProductStatus.ON_SALE);
        salesPostFacade.wishProduct(MEMBER_ID, productId);
        changePostStatus(postId, SalesPostSaleStatus.CLOSED);

        salesPostFacade.wishProduct(MEMBER_ID, productId);

        assertThat(salesPostProductWishRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 찜 요청이 동시에 여러 번 들어와도 모두 성공하고 찜은 한 줄만 남는다")
    void concurrentWishKeepsOneRow() throws Exception {
        Long productId = saveProduct(savePost(SalesPostSaleStatus.ON_SALE), SalesPostProductStatus.ON_SALE);
        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            results.add(executor.submit(() -> {
                start.await();
                salesPostFacade.wishProduct(MEMBER_ID, productId);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> result : results) {
            result.get(10, TimeUnit.SECONDS);   // 예외가 났으면 여기서 다시 던져진다
        }
        executor.shutdown();

        assertThat(salesPostProductWishRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("판매 종료된 판매글의 상품은 찜할 수 없다")
    void closedPostIsNotWishable() {
        Long productId = saveProduct(savePost(SalesPostSaleStatus.CLOSED), SalesPostProductStatus.ON_SALE);

        assertError(() -> salesPostFacade.wishProduct(MEMBER_ID, productId), SalesPostErrorCode.WISH_NOT_ALLOWED);
        assertThat(salesPostProductWishRepository.count()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = SalesPostSaleStatus.class, names = {"SUSPENDED", "DELETED", "DELETING", "PENDING"})
    @DisplayName("다른 사용자에게 보이지 않는 판매글의 상품은 없는 상품으로 응답한다")
    void hiddenPostProductIsNotFound(SalesPostSaleStatus status) {
        Long productId = saveProduct(savePost(status), SalesPostProductStatus.ON_SALE);

        assertError(() -> salesPostFacade.wishProduct(MEMBER_ID, productId), SalesPostErrorCode.WISH_PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("삭제된 판매글 상품 · 없는 상품은 없는 상품으로 응답한다")
    void deletedOrMissingProductIsNotFound() {
        Long deletedProductId = saveProduct(savePost(SalesPostSaleStatus.ON_SALE), SalesPostProductStatus.DELETED);

        assertError(() -> salesPostFacade.wishProduct(MEMBER_ID, deletedProductId), SalesPostErrorCode.WISH_PRODUCT_NOT_FOUND);
        assertError(() -> salesPostFacade.wishProduct(MEMBER_ID, 999_999L), SalesPostErrorCode.WISH_PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("로그인하지 않으면 찜하기 · 찜 해제를 할 수 없다")
    void loginRequired() {
        Long productId = saveProduct(savePost(SalesPostSaleStatus.ON_SALE), SalesPostProductStatus.ON_SALE);

        assertError(() -> salesPostFacade.wishProduct(null, productId), SalesPostErrorCode.WISH_LOGIN_REQUIRED);
        assertError(() -> salesPostFacade.unwishProduct(null, productId), SalesPostErrorCode.WISH_LOGIN_REQUIRED);
    }

    // ===== 찜 해제 =====

    @Test
    @DisplayName("찜 해제하면 찜이 지워지고, 다른 회원의 찜은 그대로 남는다")
    void unwishRemovesOnlyMine() {
        Long productId = saveProduct(savePost(SalesPostSaleStatus.ON_SALE), SalesPostProductStatus.ON_SALE);
        salesPostFacade.wishProduct(MEMBER_ID, productId);
        salesPostFacade.wishProduct(200L, productId);

        salesPostFacade.unwishProduct(MEMBER_ID, productId);

        assertThat(salesPostProductWishRepository.existsByMemberIdAndSalesPostProductId(MEMBER_ID, productId)).isFalse();
        assertThat(salesPostProductWishRepository.existsByMemberIdAndSalesPostProductId(200L, productId)).isTrue();
    }

    @Test
    @DisplayName("찜하지 않은 상품을 찜 해제해도 성공한다")
    void unwishWithoutWishSucceeds() {
        Long productId = saveProduct(savePost(SalesPostSaleStatus.ON_SALE), SalesPostProductStatus.ON_SALE);

        salesPostFacade.unwishProduct(MEMBER_ID, productId);

        assertThat(salesPostProductWishRepository.count()).isZero();
    }

    @Test
    @DisplayName("판매글이 운영 중지돼 보이지 않아도 찜 해제는 된다")
    void unwishWorksRegardlessOfStatus() {
        Long postId = savePost(SalesPostSaleStatus.ON_SALE);
        Long productId = saveProduct(postId, SalesPostProductStatus.ON_SALE);
        salesPostFacade.wishProduct(MEMBER_ID, productId);
        changePostStatus(postId, SalesPostSaleStatus.SUSPENDED);

        salesPostFacade.unwishProduct(MEMBER_ID, productId);

        assertThat(salesPostProductWishRepository.count()).isZero();
    }

    private Long savePost(SalesPostSaleStatus status) {
        return salesPostRepository.save(new SalesPost(SELLER_ID, "판매글", "내용", status, 0L,
                SalesPostUpdateStatus.NEW, LocalDateTime.now())).getId();
    }

    // 판매글의 상품 복제본 (id = 상품 id)
    private Long saveProduct(Long postId, SalesPostProductStatus status) {
        SalesPost post = salesPostRepository.findById(postId).orElseThrow();
        Long id = PRODUCT_SEQ.incrementAndGet();
        salesPostProductRepository.save(new SalesPostProduct(id, SELLER_ID, post, 1L, "반팔 티셔츠", "설명", 10000L,
                status, LocalDateTime.now(), LocalDateTime.now()));
        return id;
    }

    // 판매 종료로 바꾸는 도메인 메서드가 없어 상태 칸을 직접 바꾼다
    private void changePostStatus(Long postId, SalesPostSaleStatus status) {
        jdbcTemplate.update("update post_sales_post set status = ? where id = ?", status.name(), postId);
    }

    private void assertError(ThrowingCallable call, ErrorCode errorCode) {
        assertThatThrownBy(call)
                .isInstanceOf(ServiceException.class)
                .extracting("resultCode")
                .isEqualTo(errorCode.resultCode());
    }
}
