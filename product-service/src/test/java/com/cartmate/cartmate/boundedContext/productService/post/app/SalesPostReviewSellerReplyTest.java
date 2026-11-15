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
import com.cartmate.cartmate.boundedContext.productService.post.dto.SalesPostReviewSellerReplyRequest;
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

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * 리뷰의 판매자 답글: 그 판매글의 판매자만, 리뷰마다 1개, 수정하면 "(수정됨)", 삭제하면 칸을 비워 다시 쓸 수 있다.
 */
@SpringBootTest(properties = "cartmate.scheduling.enabled=false")
@ActiveProfiles("test")
class SalesPostReviewSellerReplyTest {

    private static final Long SELLER_ID = 7L;              // 이 판매글의 판매자
    private static final Long SELLER_MEMBER_ID = 70L;
    private static final Long OTHER_SELLER_ID = 8L;        // 다른 판매글의 판매자
    private static final Long OTHER_SELLER_MEMBER_ID = 80L;
    private static final Long BUYER_ID = 100L;

    private static final AtomicLong ORDER_ITEM_SEQ = new AtomicLong(4000);
    private static final AtomicLong PRODUCT_SEQ = new AtomicLong(39000);

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
    Long reviewId;

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
        reviewId = writeReview(postId);
    }

    @Test
    @DisplayName("이 판매글의 판매자는 답글을 쓰고, 수정하면 수정됨으로 표시된다")
    void writeAndEdit() {
        writeReply(SELLER_MEMBER_ID, SELLER_ID, "구매해 주셔서 감사합니다.");

        SalesPostReview written = review();
        assertThat(written.getSellerReplyContent()).isEqualTo("구매해 주셔서 감사합니다.");
        assertThat(written.getSellerRepliedAt()).isNotNull();
        assertThat(written.isSellerReplyEdited()).isFalse();

        salesPostFacade.editReviewSellerReply(reviewId, SELLER_MEMBER_ID, SELLER_ID,
                new SalesPostReviewSellerReplyRequest("구매해 주셔서 감사합니다! 또 찾아 주세요."));

        SalesPostReview edited = review();
        assertThat(edited.getSellerReplyContent()).isEqualTo("구매해 주셔서 감사합니다! 또 찾아 주세요.");
        assertThat(edited.isSellerReplyEdited()).isTrue();
    }

    @Test
    @DisplayName("내용이 그대로인 수정은 수정됨으로 표시하지 않는다")
    void sameContentEditChangesNothing() {
        writeReply(SELLER_MEMBER_ID, SELLER_ID, "구매해 주셔서 감사합니다.");

        salesPostFacade.editReviewSellerReply(reviewId, SELLER_MEMBER_ID, SELLER_ID,
                new SalesPostReviewSellerReplyRequest("구매해 주셔서 감사합니다."));

        assertThat(review().isSellerReplyEdited()).isFalse();
    }

    @Test
    @DisplayName("답글을 삭제하면 칸이 비고, 다시 쓸 수 있다")
    void deleteThenRewrite() {
        writeReply(SELLER_MEMBER_ID, SELLER_ID, "첫 답글");
        salesPostFacade.editReviewSellerReply(reviewId, SELLER_MEMBER_ID, SELLER_ID,
                new SalesPostReviewSellerReplyRequest("고친 답글"));

        salesPostFacade.deleteReviewSellerReply(reviewId, SELLER_MEMBER_ID, SELLER_ID);

        SalesPostReview deleted = review();
        assertThat(deleted.hasSellerReply()).isFalse();
        assertThat(deleted.getSellerRepliedAt()).isNull();
        assertThat(deleted.isSellerReplyEdited()).isFalse();

        writeReply(SELLER_MEMBER_ID, SELLER_ID, "다시 쓴 답글");
        assertThat(review().getSellerReplyContent()).isEqualTo("다시 쓴 답글");
    }

    @Test
    @DisplayName("구매자와 다른 판매글의 판매자는 답글을 쓰거나 고치거나 지울 수 없다")
    void onlyPostSeller() {
        assertError(() -> writeReply(BUYER_ID, null, "구매자가 쓰는 답글"), SalesPostErrorCode.REVIEW_SELLER_REPLY_DENIED);
        assertError(() -> writeReply(OTHER_SELLER_MEMBER_ID, OTHER_SELLER_ID, "다른 판매자 답글"),
                SalesPostErrorCode.REVIEW_SELLER_REPLY_DENIED);

        writeReply(SELLER_MEMBER_ID, SELLER_ID, "판매자 답글");
        assertError(() -> salesPostFacade.editReviewSellerReply(reviewId, OTHER_SELLER_MEMBER_ID, OTHER_SELLER_ID,
                new SalesPostReviewSellerReplyRequest("바꿈")), SalesPostErrorCode.REVIEW_SELLER_REPLY_DENIED);
        assertError(() -> salesPostFacade.deleteReviewSellerReply(reviewId, BUYER_ID, null),
                SalesPostErrorCode.REVIEW_SELLER_REPLY_DENIED);
    }

    @Test
    @DisplayName("이미 답글이 있으면 또 쓸 수 없고, 답글이 없으면 고치거나 지울 수 없다")
    void oneReplyPerReview() {
        assertError(() -> salesPostFacade.editReviewSellerReply(reviewId, SELLER_MEMBER_ID, SELLER_ID,
                new SalesPostReviewSellerReplyRequest("바꿈")), SalesPostErrorCode.REVIEW_SELLER_REPLY_NOT_FOUND);
        assertError(() -> salesPostFacade.deleteReviewSellerReply(reviewId, SELLER_MEMBER_ID, SELLER_ID),
                SalesPostErrorCode.REVIEW_SELLER_REPLY_NOT_FOUND);

        writeReply(SELLER_MEMBER_ID, SELLER_ID, "첫 답글");
        assertError(() -> writeReply(SELLER_MEMBER_ID, SELLER_ID, "두 번째 답글"),
                SalesPostErrorCode.REVIEW_SELLER_REPLY_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("답글 내용은 1~1000자여야 한다")
    void validatesContent() {
        assertError(() -> writeReply(SELLER_MEMBER_ID, SELLER_ID, " "), SalesPostErrorCode.REVIEW_SELLER_REPLY_INVALID_CONTENT);
        assertError(() -> writeReply(SELLER_MEMBER_ID, SELLER_ID, "가".repeat(1001)),
                SalesPostErrorCode.REVIEW_SELLER_REPLY_INVALID_CONTENT);

        writeReply(SELLER_MEMBER_ID, SELLER_ID, "가".repeat(1000));
        assertThat(review().getSellerReplyContent()).hasSize(1000);
    }

    @Test
    @DisplayName("삭제된 리뷰에는 답글을 쓸 수 없다")
    void deletedReview() {
        salesPostFacade.deleteReview(reviewId, BUYER_ID);

        assertError(() -> writeReply(SELLER_MEMBER_ID, SELLER_ID, "답글"), SalesPostErrorCode.REVIEW_NOT_FOUND);
    }

    @Test
    @DisplayName("운영 중지된 판매글의 리뷰에는 답글을 쓸 수 없고, 판매 종료된 판매글에는 쓸 수 있다")
    void postVisibility() {
        Long closedPost = savePost(SalesPostSaleStatus.CLOSED);
        Long onClosed = writeReview(closedPost);
        salesPostFacade.writeReviewSellerReply(onClosed, SELLER_MEMBER_ID, SELLER_ID,
                new SalesPostReviewSellerReplyRequest("감사합니다."));
        assertThat(salesPostReviewRepository.findById(onClosed).orElseThrow().hasSellerReply()).isTrue();

        SalesPost post = salesPostRepository.findById(postId).orElseThrow();
        post.suspendByAdmin();
        salesPostRepository.save(post);
        assertError(() -> writeReply(SELLER_MEMBER_ID, SELLER_ID, "답글"), SalesPostErrorCode.POST_NOT_FOUND);
    }

    @Test
    @DisplayName("로그인하지 않으면 막는다")
    void requiresLogin() {
        assertError(() -> writeReply(null, null, "답글"), SalesPostErrorCode.COMMENT_LOGIN_REQUIRED);
    }

    private void writeReply(Long memberId, Long sellerId, String content) {
        salesPostFacade.writeReviewSellerReply(reviewId, memberId, sellerId, new SalesPostReviewSellerReplyRequest(content));
    }

    private SalesPostReview review() {
        return salesPostReviewRepository.findById(reviewId).orElseThrow();
    }

    // 판매글에 상품 복제본 · 구매확정된 구매 기록을 만들고 구매자가 리뷰를 쓴다
    private Long writeReview(Long postId) {
        SalesPost post = salesPostRepository.findById(postId).orElseThrow();
        Long productId = PRODUCT_SEQ.incrementAndGet();
        salesPostProductRepository.save(new SalesPostProduct(productId, SELLER_ID, post, 1L, "반팔 티셔츠", "설명", 10000L,
                SalesPostProductStatus.ON_SALE, LocalDateTime.now(), LocalDateTime.now()));
        SalesPostPurchase purchase = SalesPostPurchase.purchased(PurchaseType.INDIVIDUAL_GROUP, 31L,
                ORDER_ITEM_SEQ.incrementAndGet(), BUYER_ID, productId, productId * 10, SELLER_ID, "반팔 티셔츠", "블랙 / L");
        purchase.confirm(LocalDateTime.now().minusDays(1));
        Long purchaseId = salesPostPurchaseRepository.save(purchase).getId();
        return salesPostFacade.createReview(BUYER_ID,
                new CreateSalesPostReviewRequest(purchaseId, 2, "생각보다 얇아서 아쉬워요.")).reviewId();
    }

    private Long savePost(SalesPostSaleStatus status) {
        return salesPostRepository.save(new SalesPost(SELLER_ID, "판매글", "내용", status, 0L,
                SalesPostUpdateStatus.NEW, LocalDateTime.now())).getId();
    }

    private void assertError(ThrowingCallable call, ErrorCode errorCode) {
        assertThatThrownBy(call)
                .isInstanceOf(ServiceException.class)
                .extracting("resultCode")
                .isEqualTo(errorCode.resultCode());
    }
}
