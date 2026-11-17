package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostPurchase;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostReview;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostReviewHistory;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostReviewRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.UpdateSalesPostReviewRequest;
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
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * 리뷰 수정 · 삭제.
 * 수정: 작성자 본인 · 구매확정 후 30일 안 · 이력 보관 · 별점 합계에 차이만 반영.
 * 삭제: 작성자 본인 · 언제든 · soft delete · 리뷰 수 · 별점 합계 감소 · 다시 쓸 수 없음.
 */
@SpringBootTest(properties = "cartmate.scheduling.enabled=false")
@ActiveProfiles("test")
class SalesPostReviewUpdateDeleteTest {

    private static final Long SELLER_ID = 7L;
    private static final Long BUYER_ID = 100L;
    private static final Long OTHER_BUYER_ID = 200L;
    private static final String CONTENT = "사이즈가 딱 맞고 품질도 좋아요.";

    private static final AtomicLong ORDER_ITEM_SEQ = new AtomicLong(3000);
    private static final AtomicLong PRODUCT_SEQ = new AtomicLong(29000);

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
    @DisplayName("수정하면 별점 · 내용이 바뀌고 수정됨으로 표시되며, 수정 전 값이 순서대로 이력에 쌓이고 별점 합계에는 차이만 반영된다")
    void editSavesHistoryAndAdjustsSum() {
        Long reviewId = write(BUYER_ID, savePurchase(BUYER_ID), 5);

        update(reviewId, BUYER_ID, 3, "며칠 써 보니 보풀이 생겨요.");
        update(reviewId, BUYER_ID, 2, "세탁 후 줄어들었어요. 아쉬워요.");

        SalesPostReview review = salesPostReviewRepository.findById(reviewId).orElseThrow();
        assertThat(review.getRating()).isEqualTo(2);
        assertThat(review.getContent()).isEqualTo("세탁 후 줄어들었어요. 아쉬워요.");
        assertThat(review.isEdited()).isTrue();
        assertThat(historiesOf(reviewId))
                .extracting(SalesPostReviewHistory::getBeforeRating, SalesPostReviewHistory::getBeforeContent)
                .containsExactly(tuple(5, CONTENT), tuple(3, "며칠 써 보니 보풀이 생겨요."));

        SalesPost post = salesPostRepository.findById(postId).orElseThrow();
        assertThat(post.getReviewCount()).isEqualTo(1);
        assertThat(post.getReviewRatingSum()).isEqualTo(2);
    }

    @Test
    @DisplayName("별점 · 내용이 그대로면 이력도 수정됨 표시도 남기지 않는다")
    void sameValuesChangeNothing() {
        Long reviewId = write(BUYER_ID, savePurchase(BUYER_ID), 5);

        update(reviewId, BUYER_ID, 5, CONTENT);

        assertThat(salesPostReviewRepository.findById(reviewId).orElseThrow().isEdited()).isFalse();
        assertThat(historiesOf(reviewId)).isEmpty();
    }

    @Test
    @DisplayName("수정 기한(구매확정 후 30일)이 지나면 수정할 수 없지만 삭제는 할 수 있다")
    void editPeriod() {
        Long reviewId = write(BUYER_ID, savePurchase(BUYER_ID), 5);
        SalesPostReview review = salesPostReviewRepository.findById(reviewId).orElseThrow();
        ReflectionTestUtils.setField(review, "editableUntil", LocalDateTime.now().minusMinutes(1));
        salesPostReviewRepository.save(review);

        assertError(() -> update(reviewId, BUYER_ID, 1, "기한 지나서 고치려는 내용"), SalesPostErrorCode.REVIEW_EDIT_PERIOD_EXPIRED);
        salesPostFacade.deleteReview(reviewId, BUYER_ID);
        assertThat(salesPostReviewRepository.findById(reviewId).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("남의 리뷰는 수정 · 삭제할 수 없다")
    void onlyWriter() {
        Long reviewId = write(BUYER_ID, savePurchase(BUYER_ID), 5);

        assertError(() -> update(reviewId, OTHER_BUYER_ID, 1, "남이 고치려는 내용입니다"), SalesPostErrorCode.REVIEW_EDIT_DENIED);
        assertError(() -> salesPostFacade.deleteReview(reviewId, OTHER_BUYER_ID), SalesPostErrorCode.REVIEW_DELETE_DENIED);
    }

    @Test
    @DisplayName("수정 내용도 별점 1~5 · 내용 10~1000자여야 한다")
    void editValidates() {
        Long reviewId = write(BUYER_ID, savePurchase(BUYER_ID), 5);

        assertError(() -> update(reviewId, BUYER_ID, 6, CONTENT), SalesPostErrorCode.REVIEW_INVALID_RATING);
        assertError(() -> update(reviewId, BUYER_ID, 4, "짧음"), SalesPostErrorCode.REVIEW_INVALID_CONTENT);
    }

    @Test
    @DisplayName("삭제하면 표시만 하고, 판매글 리뷰 수 · 별점 합계가 줄어든다")
    void deleteDecreasesSummary() {
        Long first = write(BUYER_ID, savePurchase(BUYER_ID), 5);
        write(OTHER_BUYER_ID, savePurchase(OTHER_BUYER_ID), 3);

        salesPostFacade.deleteReview(first, BUYER_ID);

        SalesPostReview deleted = salesPostReviewRepository.findById(first).orElseThrow();
        assertThat(deleted.isDeleted()).isTrue();
        assertThat(deleted.getContent()).isEqualTo(CONTENT);
        SalesPost post = salesPostRepository.findById(postId).orElseThrow();
        assertThat(post.getReviewCount()).isEqualTo(1);
        assertThat(post.getReviewRatingSum()).isEqualTo(3);
    }

    @Test
    @DisplayName("삭제한 리뷰는 다시 쓸 수 없고, 쓸 수 있는 목록에도 나오지 않는다")
    void cannotRewriteAfterDelete() {
        Long purchaseId = savePurchase(BUYER_ID);
        Long reviewId = write(BUYER_ID, purchaseId, 5);
        salesPostFacade.deleteReview(reviewId, BUYER_ID);

        assertError(() -> write(BUYER_ID, purchaseId, 4), SalesPostErrorCode.REVIEW_ALREADY_WRITTEN);
        assertThat(salesPostFacade.selectWritableReviews(BUYER_ID))
                .extracting(WritableSalesPostReviewResponse::purchaseId).doesNotContain(purchaseId);
    }

    @Test
    @DisplayName("삭제된 리뷰는 수정하거나 다시 삭제할 수 없고, 집계도 한 번만 줄어든다")
    void deletedReviewCannotBeTouched() {
        Long reviewId = write(BUYER_ID, savePurchase(BUYER_ID), 5);
        salesPostFacade.deleteReview(reviewId, BUYER_ID);

        assertError(() -> update(reviewId, BUYER_ID, 1, "삭제 후 고치려는 내용"), SalesPostErrorCode.REVIEW_NOT_FOUND);
        assertError(() -> salesPostFacade.deleteReview(reviewId, BUYER_ID), SalesPostErrorCode.REVIEW_NOT_FOUND);
        assertThat(salesPostRepository.findById(postId).orElseThrow().getReviewCount()).isZero();
    }

    @Test
    @DisplayName("운영 중지된 판매글의 리뷰는 없는 글로 보고 수정 · 삭제할 수 없다")
    void hiddenPost() {
        Long reviewId = write(BUYER_ID, savePurchase(BUYER_ID), 5);
        SalesPost post = salesPostRepository.findById(postId).orElseThrow();
        post.suspendByAdmin();
        salesPostRepository.save(post);

        assertError(() -> update(reviewId, BUYER_ID, 1, "숨겨진 글에서 고치는 내용"), SalesPostErrorCode.POST_NOT_FOUND);
        assertError(() -> salesPostFacade.deleteReview(reviewId, BUYER_ID), SalesPostErrorCode.POST_NOT_FOUND);
    }

    @Test
    @DisplayName("없는 리뷰는 없음으로, 로그인하지 않으면 로그인 필요로 응답한다")
    void notFoundAndLogin() {
        Long reviewId = write(BUYER_ID, savePurchase(BUYER_ID), 5);

        assertError(() -> update(999_999L, BUYER_ID, 1, "없는 리뷰를 고치는 내용"), SalesPostErrorCode.REVIEW_NOT_FOUND);
        assertError(() -> update(reviewId, null, 1, "로그인 없이 고치는 내용"), SalesPostErrorCode.COMMENT_LOGIN_REQUIRED);
        assertError(() -> salesPostFacade.deleteReview(reviewId, null), SalesPostErrorCode.COMMENT_LOGIN_REQUIRED);
    }

    private void update(Long reviewId, Long memberId, int rating, String content) {
        salesPostFacade.updateReview(reviewId, memberId, new UpdateSalesPostReviewRequest(rating, content));
    }

    private Long write(Long memberId, Long purchaseId, int rating) {
        return salesPostFacade.createReview(memberId, new CreateSalesPostReviewRequest(purchaseId, rating, CONTENT))
                .reviewId();
    }

    private List<SalesPostReviewHistory> historiesOf(Long reviewId) {
        return salesPostReviewHistoryRepository.findAll().stream()
                .filter(h -> h.getReviewId().equals(reviewId))
                .sorted(Comparator.comparing(SalesPostReviewHistory::getId))
                .toList();
    }

    private Long savePurchase(Long buyerId) {
        SalesPostPurchase purchase = SalesPostPurchase.purchased(PurchaseType.INDIVIDUAL_GROUP, 31L,
                ORDER_ITEM_SEQ.incrementAndGet(), buyerId, productId, productId * 10, SELLER_ID, "반팔 티셔츠", "블랙 / L");
        purchase.confirm(LocalDateTime.now().minusDays(1));
        return salesPostPurchaseRepository.save(purchase).getId();
    }

    private Long savePost(SalesPostSaleStatus status) {
        return salesPostRepository.save(new SalesPost(SELLER_ID, "판매글", "내용", status, 0L,
                SalesPostUpdateStatus.NEW, LocalDateTime.now())).getId();
    }

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
