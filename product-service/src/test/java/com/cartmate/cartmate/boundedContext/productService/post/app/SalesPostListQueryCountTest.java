package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductImage;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductOption;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectSalesPostRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectSalesPostResponse;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductOptionRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.global.page.PageResponse;
import com.cartmate.cartmate.support.ProductServiceTestSupport;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판매글 목록(홈 · 공개 목록)의 쿼리 수: 판매글 · 상품 수가 늘어도 쿼리 수가 늘지 않아야 한다(N+1 없음).
 * 응답을 만들 때 판매글 → 상품, 상품 → 사진 · 옵션을 지연 로딩으로 읽으므로, 하나씩 조회하면 판매글 · 상품 수만큼 쿼리가 늘어난다.
 */
class SalesPostListQueryCountTest extends ProductServiceTestSupport {

    private static final Long SELLER_ID = 7L;
    private static final int PRODUCTS_PER_POST = 2;
    private static final int IMAGES_PER_PRODUCT = 2;
    private static final int OPTIONS_PER_PRODUCT = 2;

    // 상품 · 사진 · 옵션은 product 서비스의 복제본이라 id를 직접 정한다
    private static final AtomicLong PRODUCT_SEQ = new AtomicLong(900_000);
    private static final AtomicLong IMAGE_SEQ = new AtomicLong(900_000);
    private static final AtomicLong OPTION_SEQ = new AtomicLong(900_000);

    @Autowired
    SalesPostFacade salesPostFacade;
    @Autowired
    SalesPostRepository salesPostRepository;
    @Autowired
    SalesPostProductRepository salesPostProductRepository;
    @Autowired
    SalesPostProductImageRepository salesPostProductImageRepository;
    @Autowired
    SalesPostProductOptionRepository salesPostProductOptionRepository;
    @Autowired
    EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void setUp() {
        salesPostProductImageRepository.deleteAll();
        salesPostProductOptionRepository.deleteAll();
        salesPostProductRepository.deleteAll();
        salesPostRepository.deleteAll();
    }

    @Test
    @DisplayName("판매글 목록은 판매글 수와 관계없이 같은 수의 쿼리로 상품 · 사진 · 옵션까지 읽는다")
    void queryCountDoesNotGrowWithPosts() {
        savePosts(5);
        long fivePosts = countQueries(5);

        savePosts(15);
        long twentyPosts = countQueries(20);

        assertThat(twentyPosts)
                .as("판매글 5개: %d번, 20개: %d번", fivePosts, twentyPosts)
                .isEqualTo(fivePosts);
    }

    @Test
    @DisplayName("쿼리 수를 줄여도 응답에는 판매글마다 상품 · 사진 · 옵션이 모두 들어간다")
    void responseHasAllProductsImagesAndOptions() {
        savePosts(3);

        PageResponse<SelectSalesPostResponse> page = list(20);

        assertThat(page.content()).hasSize(3);
        assertThat(page.content()).allSatisfy(post -> {
            assertThat(post.getProductList()).hasSize(PRODUCTS_PER_POST);
            assertThat(post.getProductList()).allSatisfy(product -> {
                assertThat(product.getProductImageList()).hasSize(IMAGES_PER_PRODUCT);
                assertThat(product.getProductOptionList()).hasSize(OPTIONS_PER_PRODUCT);
            });
        });
    }

    // 목록 조회 한 번에 실행된 SQL 수
    // 통계는 설정(generate_statistics)이 아니라 여기서 켜고 끈다. 설정으로 켜면 다른 테스트와 컨텍스트를 같이 쓰지 못한다
    private long countQueries(int pageSize) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            PageResponse<SelectSalesPostResponse> page = list(pageSize);
            assertThat(page.content()).hasSize(pageSize);
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    private PageResponse<SelectSalesPostResponse> list(int pageSize) {
        return salesPostFacade.selectSalesPostAll(new SelectSalesPostRequest(), PageRequest.of(0, pageSize)).getData();
    }

    private void savePosts(int count) {
        for (int i = 0; i < count; i++) {
            SalesPost post = salesPostRepository.save(new SalesPost(SELLER_ID, "판매글 " + i, "내용",
                    SalesPostSaleStatus.ON_SALE, 0L, SalesPostUpdateStatus.NEW, LocalDateTime.now()));
            for (int p = 0; p < PRODUCTS_PER_POST; p++) {
                saveProduct(post);
            }
        }
    }

    private void saveProduct(SalesPost post) {
        LocalDateTime now = LocalDateTime.now();
        SalesPostProduct product = salesPostProductRepository.save(new SalesPostProduct(PRODUCT_SEQ.incrementAndGet(),
                SELLER_ID, post, 1L, "반팔 티셔츠", "설명", 10000L, SalesPostProductStatus.ON_SALE, now, now));
        for (int i = 0; i < IMAGES_PER_PRODUCT; i++) {
            salesPostProductImageRepository.save(new SalesPostProductImage(IMAGE_SEQ.incrementAndGet(), SELLER_ID,
                    product, "images/" + product.getId() + "-" + i + ".jpg", i + 1, SalesPostProductStatus.ACTIVE,
                    now, now));
        }
        for (int i = 0; i < OPTIONS_PER_PRODUCT; i++) {
            salesPostProductOptionRepository.save(new SalesPostProductOption(OPTION_SEQ.incrementAndGet(), product,
                    1L, (long) (i + 1), SalesPostProductStatus.ACTIVE, now, now));
        }
    }
}
