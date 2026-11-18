package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostComment;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostPurchase;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostReview;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostCommentHistoryRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostCommentRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductOptionRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostPurchaseRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostReviewHistoryRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostReviewRepository;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import com.cartmate.cartmate.support.ProductServiceTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 탈퇴가 완료된 회원들이 쓴 댓글 · 답글 · 리뷰의 삭제 처리(soft delete)와 판매글 리뷰 집계 반영.
 * 탈퇴 완료 회원 ID는 스케줄러가 회원 서버에서 받아 넘긴다 — 여기서는 UseCase를 직접 부른다.
 */
class SalesPostWriterWithdrawTest extends ProductServiceTestSupport {

    private static final Long SELLER_ID = 7L;
    private static final Long WITHDRAWN_MEMBER_ID = 100L;
    private static final Long OTHER_WITHDRAWN_MEMBER_ID = 101L;
    private static final Long OTHER_MEMBER_ID = 200L;
    private static final AtomicLong ORDER_ITEM_SEQ = new AtomicLong(5000);

    @Autowired
    SalesPostWriterWithdrawUseCase salesPostWriterWithdrawUseCase;
    @Autowired
    SalesPostRepository salesPostRepository;
    @Autowired
    SalesPostCommentRepository salesPostCommentRepository;
    @Autowired
    SalesPostCommentHistoryRepository salesPostCommentHistoryRepository;
    @Autowired
    SalesPostReviewRepository salesPostReviewRepository;
    @Autowired
    SalesPostReviewHistoryRepository salesPostReviewHistoryRepository;
    @Autowired
    SalesPostPurchaseRepository salesPostPurchaseRepository;
    @Autowired
    SalesPostProductRepository salesPostProductRepository;
    @Autowired
    SalesPostProductImageRepository salesPostProductImageRepository;
    @Autowired
    SalesPostProductOptionRepository salesPostProductOptionRepository;

    Long postId;
    Long otherPostId;

    @BeforeEach
    void setUp() {
        salesPostCommentHistoryRepository.deleteAll();
        salesPostCommentRepository.deleteAll();
        salesPostReviewHistoryRepository.deleteAll();
        salesPostReviewRepository.deleteAll();
        salesPostPurchaseRepository.deleteAll();
        salesPostProductImageRepository.deleteAll();
        salesPostProductOptionRepository.deleteAll();
        salesPostProductRepository.deleteAll();
        salesPostRepository.deleteAll();

        postId = savePost();
        otherPostId = savePost();
    }

    @Test
    @DisplayName("그 회원들이 쓴 원댓글 · 답글만 판매글과 상관없이 삭제 처리되고, 다른 회원의 댓글은 그대로다")
    void deletesOnlyThoseMembersComments() {
        SalesPostComment root = saveComment(SalesPostComment.root(postId, WITHDRAWN_MEMBER_ID, "탈퇴회원", "정사이즈인가요?", true));
        SalesPostComment othersRoot = saveComment(SalesPostComment.root(otherPostId, OTHER_MEMBER_ID, "남은회원", "배송 얼마나?", false));
        SalesPostComment replyOnOthers = saveComment(SalesPostComment.reply(othersRoot, OTHER_WITHDRAWN_MEMBER_ID, "탈퇴회원2", "저도 궁금", false));

        SalesPostWriterWithdrawUseCase.Result result =
                salesPostWriterWithdrawUseCase.deleteWrittenBy(List.of(WITHDRAWN_MEMBER_ID, OTHER_WITHDRAWN_MEMBER_ID));

        assertThat(result.deletedComments()).isEqualTo(2);
        assertThat(comment(root).isDeleted()).isTrue();
        assertThat(comment(root).getContent()).isEqualTo("정사이즈인가요?");   // soft delete: 내용은 DB에 남는다
        assertThat(comment(replyOnOthers).isDeleted()).isTrue();
        assertThat(comment(othersRoot).isDeleted()).isFalse();
    }

    @Test
    @DisplayName("그 회원들의 리뷰가 삭제 처리되고, 판매글마다 리뷰 수 · 별점 합계에서 빠진다")
    void deletesReviewsAndAdjustsSummaryPerPost() {
        SalesPostReview onPost = saveReview(postId, WITHDRAWN_MEMBER_ID, 5);
        SalesPostReview onPost2 = saveReview(postId, OTHER_WITHDRAWN_MEMBER_ID, 2);
        SalesPostReview onOtherPost = saveReview(otherPostId, WITHDRAWN_MEMBER_ID, 4);
        SalesPostReview others = saveReview(postId, OTHER_MEMBER_ID, 3);

        SalesPostWriterWithdrawUseCase.Result result =
                salesPostWriterWithdrawUseCase.deleteWrittenBy(List.of(WITHDRAWN_MEMBER_ID, OTHER_WITHDRAWN_MEMBER_ID));

        assertThat(result.deletedReviews()).isEqualTo(3);
        assertThat(review(onPost).isDeleted()).isTrue();
        assertThat(review(onPost2).isDeleted()).isTrue();
        assertThat(review(onOtherPost).isDeleted()).isTrue();
        assertThat(review(others).isDeleted()).isFalse();

        SalesPost post = salesPostRepository.findById(postId).orElseThrow();
        assertThat(post.getReviewCount()).isEqualTo(1);
        assertThat(post.getReviewRatingSum()).isEqualTo(3);
        SalesPost otherPost = salesPostRepository.findById(otherPostId).orElseThrow();
        assertThat(otherPost.getReviewCount()).isZero();
        assertThat(otherPost.getReviewRatingSum()).isZero();
    }

    @Test
    @DisplayName("이미 삭제된 리뷰는 집계에서 다시 빼지 않고, 같은 회원으로 두 번 실행해도 결과가 같다")
    void idempotent() {
        SalesPostReview alreadyDeleted = saveReview(postId, WITHDRAWN_MEMBER_ID, 1);
        alreadyDeleted.delete(WITHDRAWN_MEMBER_ID);
        salesPostReviewRepository.save(alreadyDeleted);
        salesPostRepository.applyReviewSummary(postId, -1, -1);   // 작성자가 지웠을 때 이미 빠진 집계
        saveReview(postId, WITHDRAWN_MEMBER_ID, 5);
        saveReview(postId, OTHER_MEMBER_ID, 3);
        saveComment(SalesPostComment.root(postId, WITHDRAWN_MEMBER_ID, "탈퇴회원", "문의", false));

        SalesPostWriterWithdrawUseCase.Result first = salesPostWriterWithdrawUseCase.deleteWrittenBy(List.of(WITHDRAWN_MEMBER_ID));
        SalesPostWriterWithdrawUseCase.Result second = salesPostWriterWithdrawUseCase.deleteWrittenBy(List.of(WITHDRAWN_MEMBER_ID));

        assertThat(first).isEqualTo(new SalesPostWriterWithdrawUseCase.Result(1, 1));
        assertThat(second).isEqualTo(new SalesPostWriterWithdrawUseCase.Result(0, 0));
        SalesPost post = salesPostRepository.findById(postId).orElseThrow();
        assertThat(post.getReviewCount()).isEqualTo(1);
        assertThat(post.getReviewRatingSum()).isEqualTo(3);
    }

    @Test
    @DisplayName("빈 목록이면 아무것도 하지 않는다")
    void emptyDoesNothing() {
        SalesPostComment comment = saveComment(SalesPostComment.root(postId, WITHDRAWN_MEMBER_ID, "회원", "문의", false));
        saveReview(postId, WITHDRAWN_MEMBER_ID, 5);

        assertThat(salesPostWriterWithdrawUseCase.deleteWrittenBy(List.of()))
                .isEqualTo(new SalesPostWriterWithdrawUseCase.Result(0, 0));
        assertThat(comment(comment).isDeleted()).isFalse();
        assertThat(salesPostRepository.findById(postId).orElseThrow().getReviewCount()).isEqualTo(1);
    }

    private SalesPostComment saveComment(SalesPostComment comment) {
        return salesPostCommentRepository.save(comment);
    }

    private SalesPostComment comment(SalesPostComment saved) {
        return salesPostCommentRepository.findById(saved.getId()).orElseThrow();
    }

    private SalesPostReview review(SalesPostReview saved) {
        return salesPostReviewRepository.findById(saved.getId()).orElseThrow();
    }

    // 구매확정된 구매 기록으로 리뷰를 저장하고, 작성 API처럼 판매글 집계에도 더한다 (작성 자격은 SalesPostReviewCreateTest에서 확인)
    private SalesPostReview saveReview(Long postId, Long memberId, int rating) {
        SalesPostPurchase purchase = SalesPostPurchase.purchased(PurchaseType.INDIVIDUAL_GROUP, 31L,
                ORDER_ITEM_SEQ.incrementAndGet(), memberId, 9L, 90L, SELLER_ID, "반팔 티셔츠", "블랙 / L");
        purchase.confirm(LocalDateTime.now().minusDays(1));
        SalesPostPurchase saved = salesPostPurchaseRepository.save(purchase);
        SalesPostReview review = salesPostReviewRepository.save(
                SalesPostReview.write(saved, postId, "회원" + memberId, rating, "사이즈가 딱 맞아요. 좋아요."));
        salesPostRepository.applyReviewSummary(postId, 1, rating);
        return review;
    }

    private Long savePost() {
        return salesPostRepository.save(new SalesPost(SELLER_ID, "판매글", "내용", SalesPostSaleStatus.ON_SALE, 0L,
                SalesPostUpdateStatus.NEW, LocalDateTime.now())).getId();
    }
}
