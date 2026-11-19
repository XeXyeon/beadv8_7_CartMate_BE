package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostPurchase;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostReview;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductOptionRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostPurchaseRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostReviewRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import com.cartmate.cartmate.support.ProductServiceTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 리뷰 저장 구조: 생성 시 별점 · 내용 검증, 구매 기록에서 상품명 · 선택된 옵션 · 수정 기한 복사,
 * 주문항목(구매 기록)마다 리뷰 1개(유니크 키), 판매글 리뷰 집계 칸의 시작값.
 */
class SalesPostReviewStructureTest extends ProductServiceTestSupport {

    private static final Long BUYER_ID = 100L;
    private static final LocalDateTime CONFIRMED_AT = LocalDateTime.of(2026, 11, 8, 10, 0);

    @Autowired
    SalesPostReviewRepository salesPostReviewRepository;
    @Autowired
    SalesPostPurchaseRepository salesPostPurchaseRepository;
    @Autowired
    SalesPostRepository salesPostRepository;
    @Autowired
    SalesPostProductRepository salesPostProductRepository;
    @Autowired
    SalesPostProductImageRepository salesPostProductImageRepository;
    @Autowired
    SalesPostProductOptionRepository salesPostProductOptionRepository;

    SalesPostPurchase purchase;
    Long postId;

    @BeforeEach
    void setUp() {
        salesPostReviewRepository.deleteAll();
        salesPostPurchaseRepository.deleteAll();
        salesPostProductImageRepository.deleteAll();
        salesPostProductOptionRepository.deleteAll();
        salesPostProductRepository.deleteAll();
        salesPostRepository.deleteAll();

        postId = salesPostRepository.save(new SalesPost(7L, "판매글", "내용", SalesPostSaleStatus.ON_SALE, 0L,
                SalesPostUpdateStatus.NEW, LocalDateTime.now())).getId();
        SalesPostPurchase p = SalesPostPurchase.purchased(PurchaseType.INDIVIDUAL_GROUP, 31L, 501L, BUYER_ID,
                9L, 90L, 7L, "반팔 티셔츠", "블랙 / L");
        p.confirm(CONFIRMED_AT);
        purchase = salesPostPurchaseRepository.save(p);
    }

    @Test
    @DisplayName("리뷰는 구매 기록의 구매자 · 상품명 · 선택된 옵션을 가져오고, 수정 기한은 구매확정 후 30일이다")
    void copiesFromPurchase() {
        SalesPostReview review = salesPostReviewRepository.save(
                SalesPostReview.write(purchase, postId, "회원100", 5, "사이즈가 딱 맞아요. 만족합니다."));

        SalesPostReview saved = salesPostReviewRepository.findById(review.getId()).orElseThrow();
        assertThat(saved.getSalesPostId()).isEqualTo(postId);
        assertThat(saved.getPurchaseId()).isEqualTo(purchase.getId());
        assertThat(saved.getWriterMemberId()).isEqualTo(BUYER_ID);
        assertThat(saved.getWriterNickname()).isEqualTo("회원100");
        assertThat(saved.getProductName()).isEqualTo("반팔 티셔츠");
        assertThat(saved.getOptionName()).isEqualTo("블랙 / L");
        assertThat(saved.getRating()).isEqualTo(5);
        assertThat(saved.getEditableUntil()).isEqualTo(CONFIRMED_AT.plusDays(30));
        assertThat(saved.isEdited()).isFalse();
        assertThat(saved.isDeleted()).isFalse();
        assertThat(saved.getSellerReplyContent()).isNull();
        assertThat(saved.isSellerReplyEdited()).isFalse();
    }

    @Test
    @DisplayName("별점은 1~5점만 된다")
    void validatesRating() {
        for (int rating : new int[]{0, 6}) {
            assertThatThrownBy(() -> SalesPostReview.write(purchase, postId, "회원100", rating, "열 글자가 넘는 내용입니다."))
                    .isInstanceOf(ServiceException.class)
                    .extracting("resultCode")
                    .isEqualTo(SalesPostErrorCode.REVIEW_INVALID_RATING.resultCode());
        }
    }

    @Test
    @DisplayName("내용은 10~1000자만 된다")
    void validatesContent() {
        for (String content : new String[]{"짧아요", " ".repeat(20), "가".repeat(1001)}) {
            assertThatThrownBy(() -> SalesPostReview.write(purchase, postId, "회원100", 4, content))
                    .isInstanceOf(ServiceException.class)
                    .extracting("resultCode")
                    .isEqualTo(SalesPostErrorCode.REVIEW_INVALID_CONTENT.resultCode());
        }
        assertThat(SalesPostReview.write(purchase, postId, "회원100", 4, "가".repeat(10)).getContent()).hasSize(10);
        assertThat(SalesPostReview.write(purchase, postId, "회원100", 4, "가".repeat(1000)).getContent()).hasSize(1000);
    }

    @Test
    @DisplayName("같은 구매 기록(주문항목)으로는 리뷰를 두 개 저장할 수 없다")
    void onePerPurchase() {
        salesPostReviewRepository.saveAndFlush(SalesPostReview.write(purchase, postId, "회원100", 5, "첫 번째 리뷰입니다."));

        assertThatThrownBy(() -> salesPostReviewRepository.saveAndFlush(
                SalesPostReview.write(purchase, postId, "회원100", 1, "두 번째 리뷰입니다.")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("판매글의 리뷰 수 · 별점 합계는 0에서 시작한다")
    void postReviewSummaryStartsAtZero() {
        SalesPost post = salesPostRepository.findById(postId).orElseThrow();

        assertThat(post.getReviewCount()).isZero();
        assertThat(post.getReviewRatingSum()).isZero();
    }
}
