package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductImage;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductWish;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductWishDisplayStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SalesPostProductWishResponse;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductOptionRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductWishRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.page.PageResponse;
import com.cartmate.cartmate.support.ProductServiceTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 내 찜 목록: 찜한 순서(최근 찜 먼저), 한 페이지 20개.
 * 삭제된 판매글 상품 · 삭제(중) · 등록 중인 판매글은 빠지고, 나머지는 판매글 · 판매글 상품 상태를 합친 표시 상태를 붙인다.
 * 볼 수 없는 상품(운영 중지)은 상품명 · 대표 이미지 · 가격을 비운다.
 */
class SalesPostProductWishSelectTest extends ProductServiceTestSupport {

    private static final Long SELLER_ID = 7L;
    private static final Long MEMBER_ID = 100L;
    private static final Long OTHER_MEMBER_ID = 200L;
    private static final AtomicLong PRODUCT_SEQ = new AtomicLong(9700);
    private static final AtomicLong IMAGE_SEQ = new AtomicLong(9700);

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

    @BeforeEach
    void setUp() {
        salesPostProductWishRepository.deleteAll();
        salesPostProductImageRepository.deleteAll();
        salesPostProductOptionRepository.deleteAll();
        salesPostProductRepository.deleteAll();
        salesPostRepository.deleteAll();
    }

    @Test
    @DisplayName("최근에 찜한 상품부터 판매글 · 상품 정보와 찜한 시각을 보여준다")
    void listsByRecentWish() {
        Long postId = savePost(SalesPostSaleStatus.ON_SALE);
        Long first = saveProduct(postId, SalesPostProductStatus.ON_SALE);
        Long second = saveProduct(postId, SalesPostProductStatus.ON_SALE);
        wish(MEMBER_ID, first);
        wish(MEMBER_ID, second);

        PageResponse<SalesPostProductWishResponse> page = salesPostFacade.selectMyWishes(MEMBER_ID, 0);

        assertThat(page.content()).extracting(SalesPostProductWishResponse::productId).containsExactly(second, first);
        SalesPostProductWishResponse latest = page.content().get(0);
        assertThat(latest.salesPostId()).isEqualTo(postId);
        assertThat(latest.productName()).isEqualTo("반팔 티셔츠");
        assertThat(latest.basePrice()).isEqualTo(10000L);
        assertThat(latest.displayStatus()).isEqualTo(SalesPostProductWishDisplayStatus.ON_SALE);
        assertThat(latest.wishedAt()).isNotNull();
    }

    @Test
    @DisplayName("다른 회원의 찜은 보이지 않는다")
    void excludesOtherMembersWishes() {
        Long productId = saveProduct(savePost(SalesPostSaleStatus.ON_SALE), SalesPostProductStatus.ON_SALE);
        wish(OTHER_MEMBER_ID, productId);

        PageResponse<SalesPostProductWishResponse> page = salesPostFacade.selectMyWishes(MEMBER_ID, 0);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    @Test
    @DisplayName("삭제된 판매글 상품, 삭제 · 삭제 중 · 등록 중인 판매글의 상품은 목록과 전체 개수에서 빠진다")
    void excludesDeleted() {
        wish(MEMBER_ID, saveProduct(savePost(SalesPostSaleStatus.ON_SALE), SalesPostProductStatus.DELETED));
        wish(MEMBER_ID, saveProduct(savePost(SalesPostSaleStatus.DELETED), SalesPostProductStatus.ON_SALE));
        wish(MEMBER_ID, saveProduct(savePost(SalesPostSaleStatus.DELETING), SalesPostProductStatus.ON_SALE));
        wish(MEMBER_ID, saveProduct(savePost(SalesPostSaleStatus.PENDING), SalesPostProductStatus.ON_SALE));
        Long kept = saveProduct(savePost(SalesPostSaleStatus.ON_SALE), SalesPostProductStatus.ON_SALE);
        wish(MEMBER_ID, kept);

        PageResponse<SalesPostProductWishResponse> page = salesPostFacade.selectMyWishes(MEMBER_ID, 0);

        assertThat(page.content()).extracting(SalesPostProductWishResponse::productId).containsExactly(kept);
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @ParameterizedTest(name = "판매글 {0} + 판매글 상품 {1} → {2}")
    @CsvSource({
            "ON_SALE,   ON_SALE,   ON_SALE",
            "PAUSED,    ON_SALE,   PAUSED",
            "ON_SALE,   PAUSED,    PAUSED",
            "ON_SALE,   UPDATING,  PAUSED",
            "CLOSED,    ON_SALE,   CLOSED",
            "ON_SALE,   CLOSED,    CLOSED",
            "CLOSED,    PAUSED,    CLOSED",
            "SUSPENDED, ON_SALE,   UNAVAILABLE",
            "ON_SALE,   SUSPENDED, UNAVAILABLE",
            "SUSPENDED, CLOSED,    UNAVAILABLE"
    })
    @DisplayName("표시 상태는 판매글 · 판매글 상품 상태를 합쳐 운영 중지 → 판매 종료 → 판매 중지 → 판매 중 순으로 정한다")
    void displayStatus(SalesPostSaleStatus postStatus, SalesPostProductStatus productStatus,
                       SalesPostProductWishDisplayStatus expected) {
        wish(MEMBER_ID, saveProduct(savePost(postStatus), productStatus));

        PageResponse<SalesPostProductWishResponse> page = salesPostFacade.selectMyWishes(MEMBER_ID, 0);

        assertThat(page.content()).singleElement()
                .extracting(SalesPostProductWishResponse::displayStatus).isEqualTo(expected);
    }

    @Test
    @DisplayName("볼 수 없는 상품은 상품명 · 대표 이미지 · 가격을 비우고 판매글 · 상품 id만 준다")
    void unavailableHidesContent() {
        Long postId = savePost(SalesPostSaleStatus.SUSPENDED);
        Long productId = saveProduct(postId, SalesPostProductStatus.ON_SALE);
        saveImage(productId, "main.jpg", 1, SalesPostProductStatus.ACTIVE);
        wish(MEMBER_ID, productId);

        SalesPostProductWishResponse row = salesPostFacade.selectMyWishes(MEMBER_ID, 0).content().get(0);

        assertThat(row.salesPostId()).isEqualTo(postId);
        assertThat(row.productId()).isEqualTo(productId);
        assertThat(row.productName()).isNull();
        assertThat(row.thumbnailS3Key()).isNull();
        assertThat(row.basePrice()).isNull();
        assertThat(row.displayStatus()).isEqualTo(SalesPostProductWishDisplayStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("대표 이미지는 활성 이미지 중 순서가 가장 앞선 것이고, 이미지가 없으면 비어 있다")
    void thumbnailIsFirstActiveImage() {
        Long postId = savePost(SalesPostSaleStatus.ON_SALE);
        Long withImages = saveProduct(postId, SalesPostProductStatus.ON_SALE);
        saveImage(withImages, "deleted-first.jpg", 1, SalesPostProductStatus.DELETED);
        saveImage(withImages, "third.jpg", 3, SalesPostProductStatus.ACTIVE);
        saveImage(withImages, "second.jpg", 2, SalesPostProductStatus.ACTIVE);
        Long withoutImages = saveProduct(postId, SalesPostProductStatus.ON_SALE);
        wish(MEMBER_ID, withImages);
        wish(MEMBER_ID, withoutImages);

        PageResponse<SalesPostProductWishResponse> page = salesPostFacade.selectMyWishes(MEMBER_ID, 0);

        assertThat(page.content())
                .extracting(SalesPostProductWishResponse::productId, SalesPostProductWishResponse::thumbnailS3Key)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(withoutImages, null),
                        org.assertj.core.groups.Tuple.tuple(withImages, "second.jpg"));
    }

    @Test
    @DisplayName("한 페이지에 20개씩 나뉜다")
    void pagesBy20() {
        Long postId = savePost(SalesPostSaleStatus.ON_SALE);
        for (int i = 0; i < 21; i++) {
            wish(MEMBER_ID, saveProduct(postId, SalesPostProductStatus.ON_SALE));
        }

        PageResponse<SalesPostProductWishResponse> first = salesPostFacade.selectMyWishes(MEMBER_ID, 0);
        PageResponse<SalesPostProductWishResponse> second = salesPostFacade.selectMyWishes(MEMBER_ID, 1);

        assertThat(first.content()).hasSize(20);
        assertThat(second.content()).hasSize(1);
        assertThat(first.totalElements()).isEqualTo(21);
        assertThat(first.totalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("로그인하지 않으면 찜 목록을 볼 수 없다")
    void loginRequired() {
        assertThatThrownBy(() -> salesPostFacade.selectMyWishes(null, 0))
                .isInstanceOf(ServiceException.class)
                .extracting("resultCode")
                .isEqualTo(SalesPostErrorCode.WISH_LOGIN_REQUIRED.resultCode());
    }

    // 찜하기 조건(판매 중 · 판매 중지)을 거치지 않고 찜 줄을 바로 만든다 (찜한 뒤 상태가 바뀐 경우)
    private void wish(Long memberId, Long productId) {
        salesPostProductWishRepository.save(SalesPostProductWish.of(memberId, productId));
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

    private void saveImage(Long productId, String s3Key, int imageSeq, SalesPostProductStatus status) {
        SalesPostProduct product = salesPostProductRepository.findById(productId).orElseThrow();
        salesPostProductImageRepository.save(new SalesPostProductImage(IMAGE_SEQ.incrementAndGet(), SELLER_ID, product,
                s3Key, imageSeq, status, LocalDateTime.now(), LocalDateTime.now()));
    }
}
