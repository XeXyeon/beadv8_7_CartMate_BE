package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostPurchase;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostReview;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostReviewRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostReviewResponse;
import com.cartmate.cartmate.boundedContext.productService.post.dto.WritableSalesPostReviewResponse;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostMemberClient;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductOptionRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostPurchaseRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostReviewHistoryRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostReviewRepository;
import com.cartmate.cartmate.global.exception.ErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.http.InternalApiClient.InternalServerUnavailableException;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 리뷰 작성과 "리뷰를 쓸 수 있는 상품 목록".
 * 자격: 본인 구매 · 구매확정 · 구매확정 후 30일 안 · 주문항목마다 1개 · 판매글이 보이는 상태.
 */
@SpringBootTest(properties = "cartmate.scheduling.enabled=false")
@ActiveProfiles("test")
class SalesPostReviewCreateTest {

    private static final Long SELLER_ID = 7L;
    private static final Long BUYER_ID = 100L;
    private static final Long OTHER_BUYER_ID = 200L;
    private static final String CONTENT = "사이즈가 딱 맞고 품질도 좋아요.";

    private static final AtomicLong ORDER_ITEM_SEQ = new AtomicLong(1000);
    private static final AtomicLong PRODUCT_SEQ = new AtomicLong(9000);

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
    SalesPostPurchaseRepository salesPostPurchaseRepository;
    @Autowired
    SalesPostReviewRepository salesPostReviewRepository;
    @Autowired
    SalesPostReviewHistoryRepository salesPostReviewHistoryRepository;

    // 실제로는 회원 서버를 HTTP로 부르므로 대역으로 바꾼다
    @MockitoBean
    SalesPostMemberClient salesPostMemberClient;

    Long postId;
    Long productId;

    @BeforeEach
    void setUp() {
        salesPostReviewHistoryRepository.deleteAll();
        salesPostReviewRepository.deleteAll();
        salesPostPurchaseRepository.deleteAll();
        salesPostProductImageRepository.deleteAll();
        salesPostProductOptionRepository.deleteAll();
        salesPostProductRepository.deleteAll();
        salesPostRepository.deleteAll();

        given(salesPostMemberClient.findNickname(anyLong())).willAnswer(inv -> "회원" + inv.getArgument(0));
        postId = savePost(SalesPostSaleStatus.ON_SALE);
        productId = saveProduct(postId);
    }

    @Test
    @DisplayName("구매확정된 본인 구매로 리뷰를 쓰면 저장되고, 판매글의 리뷰 수 · 별점 합계에 반영된다")
    void writesReviewAndUpdatesSummary() {
        Long purchaseId = savePurchase(BUYER_ID, productId, LocalDateTime.now().minusDays(1));

        CreateSalesPostReviewResponse response = write(BUYER_ID, purchaseId, 4);

        SalesPostReview review = salesPostReviewRepository.findById(response.reviewId()).orElseThrow();
        assertThat(response.salesPostId()).isEqualTo(postId);
        assertThat(review.getSalesPostId()).isEqualTo(postId);
        assertThat(review.getWriterNickname()).isEqualTo("회원100");
        assertThat(review.getRating()).isEqualTo(4);
        assertThat(review.getProductName()).isEqualTo("반팔 티셔츠");
        assertThat(review.getOptionName()).isEqualTo("블랙 / L");

        SalesPost post = salesPostRepository.findById(postId).orElseThrow();
        assertThat(post.getReviewCount()).isEqualTo(1);
        assertThat(post.getReviewRatingSum()).isEqualTo(4);
    }

    @Test
    @DisplayName("같은 판매글에 리뷰가 여러 개면 리뷰 수 · 별점 합계가 쌓인다")
    void summaryAccumulates() {
        write(BUYER_ID, savePurchase(BUYER_ID, productId, LocalDateTime.now().minusDays(1)), 5);
        write(OTHER_BUYER_ID, savePurchase(OTHER_BUYER_ID, productId, LocalDateTime.now().minusDays(2)), 2);

        SalesPost post = salesPostRepository.findById(postId).orElseThrow();
        assertThat(post.getReviewCount()).isEqualTo(2);
        assertThat(post.getReviewRatingSum()).isEqualTo(7);
    }

    @Test
    @DisplayName("남의 구매 기록 · 없는 구매 기록 · 구매 정보가 아직 없는 기록은 모두 같은 \"없음\"으로 응답한다")
    void purchaseMustBeMine() {
        Long othersPurchase = savePurchase(OTHER_BUYER_ID, productId, LocalDateTime.now().minusDays(1));
        SalesPostPurchase confirmedOnly = salesPostPurchaseRepository.save(SalesPostPurchase.confirmedFirst(
                PurchaseType.INDIVIDUAL_GROUP, 31L, ORDER_ITEM_SEQ.incrementAndGet(), LocalDateTime.now().minusDays(1)));

        assertError(() -> write(BUYER_ID, othersPurchase, 5), SalesPostErrorCode.REVIEW_PURCHASE_NOT_FOUND);
        assertError(() -> write(BUYER_ID, 999_999L, 5), SalesPostErrorCode.REVIEW_PURCHASE_NOT_FOUND);
        assertError(() -> write(BUYER_ID, confirmedOnly.getId(), 5), SalesPostErrorCode.REVIEW_PURCHASE_NOT_FOUND);
    }

    @Test
    @DisplayName("구매확정 전에는 쓸 수 없다")
    void mustBeConfirmed() {
        Long purchaseId = savePurchase(BUYER_ID, productId, null);

        assertError(() -> write(BUYER_ID, purchaseId, 5), SalesPostErrorCode.REVIEW_NOT_CONFIRMED);
    }

    @Test
    @DisplayName("구매확정 후 30일이 지나면 쓸 수 없고, 그 안이면 쓸 수 있다")
    void writablePeriod() {
        Long expired = savePurchase(BUYER_ID, productId, LocalDateTime.now().minusDays(31));
        Long almost = savePurchase(BUYER_ID, productId, LocalDateTime.now().minusDays(29));

        assertError(() -> write(BUYER_ID, expired, 5), SalesPostErrorCode.REVIEW_PERIOD_EXPIRED);
        assertThat(write(BUYER_ID, almost, 5).reviewId()).isNotNull();
    }

    @Test
    @DisplayName("같은 주문항목으로는 한 번만 쓸 수 있고, 판매글 집계도 한 번만 반영된다")
    void onlyOncePerPurchase() {
        Long purchaseId = savePurchase(BUYER_ID, productId, LocalDateTime.now().minusDays(1));
        write(BUYER_ID, purchaseId, 5);

        assertError(() -> write(BUYER_ID, purchaseId, 1), SalesPostErrorCode.REVIEW_ALREADY_WRITTEN);
        assertThat(salesPostRepository.findById(postId).orElseThrow().getReviewCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("판매 종료된 판매글에는 쓸 수 있고, 운영 중지된 판매글은 없는 글로 응답한다")
    void postVisibility() {
        Long closedPost = savePost(SalesPostSaleStatus.CLOSED);
        Long suspendedPost = savePost(SalesPostSaleStatus.SUSPENDED);
        Long onClosed = savePurchase(BUYER_ID, saveProduct(closedPost), LocalDateTime.now().minusDays(1));
        Long onSuspended = savePurchase(BUYER_ID, saveProduct(suspendedPost), LocalDateTime.now().minusDays(1));

        assertThat(write(BUYER_ID, onClosed, 5).salesPostId()).isEqualTo(closedPost);
        assertError(() -> write(BUYER_ID, onSuspended, 5), SalesPostErrorCode.POST_NOT_FOUND);
    }

    @Test
    @DisplayName("별점 · 내용 형식이 틀리면 저장하지 않는다")
    void validatesInput() {
        Long purchaseId = savePurchase(BUYER_ID, productId, LocalDateTime.now().minusDays(1));

        assertError(() -> write(BUYER_ID, purchaseId, 0), SalesPostErrorCode.REVIEW_INVALID_RATING);
        assertError(() -> salesPostFacade.createReview(BUYER_ID, new CreateSalesPostReviewRequest(purchaseId, 5, "짧음")),
                SalesPostErrorCode.REVIEW_INVALID_CONTENT);
        assertThat(salesPostReviewRepository.count()).isZero();
    }

    @Test
    @DisplayName("로그인하지 않으면 회원 서버를 부르지 않고 막는다")
    void requiresLogin() {
        Long purchaseId = savePurchase(BUYER_ID, productId, LocalDateTime.now().minusDays(1));

        assertError(() -> write(null, purchaseId, 5), SalesPostErrorCode.COMMENT_LOGIN_REQUIRED);
        assertError(() -> salesPostFacade.selectWritableReviews(null), SalesPostErrorCode.COMMENT_LOGIN_REQUIRED);
        verify(salesPostMemberClient, never()).findNickname(anyLong());
    }

    @Test
    @DisplayName("회원 서버가 응답하지 않으면 리뷰도 집계도 저장하지 않는다")
    void failsWhenMemberServerIsDown() {
        Long purchaseId = savePurchase(BUYER_ID, productId, LocalDateTime.now().minusDays(1));
        given(salesPostMemberClient.findNickname(BUYER_ID))
                .willThrow(new InternalServerUnavailableException("MEMBER", "회원", null));

        assertThatThrownBy(() -> write(BUYER_ID, purchaseId, 5)).isInstanceOf(InternalServerUnavailableException.class);
        assertThat(salesPostReviewRepository.count()).isZero();
        assertThat(salesPostRepository.findById(postId).orElseThrow().getReviewCount()).isZero();
    }

    @Test
    @DisplayName("쓸 수 있는 목록에는 구매확정 · 기한 안 · 아직 안 쓴 · 판매글이 보이는 본인 구매만 기한이 가까운 순으로 나오고, 쓰면 빠진다")
    void writableList() {
        LocalDateTime now = LocalDateTime.now();
        Long older = savePurchase(BUYER_ID, productId, now.minusDays(20));
        Long newer = savePurchase(BUYER_ID, productId, now.minusDays(2));
        savePurchase(BUYER_ID, productId, null);                       // 구매확정 전
        savePurchase(BUYER_ID, productId, now.minusDays(31));          // 기한 지남
        savePurchase(OTHER_BUYER_ID, productId, now.minusDays(1));     // 남의 구매
        savePurchase(BUYER_ID, saveProduct(savePost(SalesPostSaleStatus.SUSPENDED)), now.minusDays(1)); // 안 보이는 판매글
        Long reviewed = savePurchase(BUYER_ID, productId, now.minusDays(3));
        write(BUYER_ID, reviewed, 5);

        List<WritableSalesPostReviewResponse> list = salesPostFacade.selectWritableReviews(BUYER_ID);

        assertThat(list).extracting(WritableSalesPostReviewResponse::purchaseId).containsExactly(older, newer);
        WritableSalesPostReviewResponse first = list.getFirst();
        assertThat(first.salesPostId()).isEqualTo(postId);
        assertThat(first.productName()).isEqualTo("반팔 티셔츠");
        assertThat(first.optionName()).isEqualTo("블랙 / L");
        assertThat(first.writableUntil()).isEqualTo(first.confirmedAt().plusDays(30));

        write(BUYER_ID, older, 4);
        assertThat(salesPostFacade.selectWritableReviews(BUYER_ID))
                .extracting(WritableSalesPostReviewResponse::purchaseId).containsExactly(newer);
    }

    private CreateSalesPostReviewResponse write(Long memberId, Long purchaseId, int rating) {
        return salesPostFacade.createReview(memberId, new CreateSalesPostReviewRequest(purchaseId, rating, CONTENT));
    }

    // confirmedAt이 null이면 구매확정 전
    private Long savePurchase(Long buyerId, Long productId, LocalDateTime confirmedAt) {
        SalesPostPurchase purchase = SalesPostPurchase.purchased(PurchaseType.INDIVIDUAL_GROUP, 31L,
                ORDER_ITEM_SEQ.incrementAndGet(), buyerId, productId, productId * 10, SELLER_ID, "반팔 티셔츠", "블랙 / L");
        if (confirmedAt != null) {
            purchase.confirm(confirmedAt);
        }
        return salesPostPurchaseRepository.save(purchase).getId();
    }

    private Long savePost(SalesPostSaleStatus status) {
        return salesPostRepository.save(new SalesPost(SELLER_ID, "판매글", "내용", status, 0L,
                SalesPostUpdateStatus.NEW, LocalDateTime.now())).getId();
    }

    // 판매글의 상품 복제본 (id = 상품 id)
    private Long saveProduct(Long postId) {
        SalesPost post = salesPostRepository.findById(postId).orElseThrow();
        Long id = PRODUCT_SEQ.incrementAndGet();
        salesPostProductRepository.save(new SalesPostProduct(id, SELLER_ID, post, 1L, "반팔 티셔츠", "설명", 10000L,
                SalesPostProductStatus.ON_SALE, LocalDateTime.now(), LocalDateTime.now()));
        return id;
    }

    private void assertError(ThrowingCallable call, ErrorCode errorCode) {
        assertThatThrownBy(call)
                .isInstanceOf(ServiceException.class)
                .extracting("resultCode")
                .isEqualTo(errorCode.resultCode());
    }
}
