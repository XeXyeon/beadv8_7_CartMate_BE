package com.cartmate.cartmate.boundedContext.productService.post.domain;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 판매글 리뷰. 구매확정된 주문항목(구매 기록) 하나에 리뷰 하나.
 *
 * <p>purchaseId가 유니크라서 주문항목마다 리뷰 1개가 DB에서 보장되고,
 * 삭제해도(soft delete) 줄이 남아 같은 주문항목으로 다시 쓸 수 없다.
 * 판매자 답글도 리뷰마다 1개라 칸으로 둔다.
 */
@Entity
@Table(name = "POST_SALES_POST_REVIEW",
        uniqueConstraints = @UniqueConstraint(name = "uk_sales_post_review_purchase", columnNames = "purchase_id"),
        indexes = {
                @Index(name = "idx_sales_post_review_post", columnList = "salesPostId"),
                @Index(name = "idx_sales_post_review_writer", columnList = "writerMemberId")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SalesPostReview extends BaseIdAndTime {

    public static final int MIN_RATING = 1;
    public static final int MAX_RATING = 5;
    public static final int MIN_CONTENT_LENGTH = 10;
    public static final int MAX_CONTENT_LENGTH = 1000;
    public static final int MAX_SELLER_REPLY_LENGTH = 1000;
    // 구매확정 후 이 기간 안에만 쓰고 고칠 수 있다
    public static final int WRITABLE_DAYS = 30;

    @Column(nullable = false)
    private Long salesPostId;

    @Column(nullable = false)
    private Long purchaseId;

    @Column(nullable = false)
    private Long writerMemberId;

    // 작성 당시 닉네임 (댓글과 같은 방식)
    @Column(nullable = false, length = 50)
    private String writerNickname;

    // 구매 기록에서 복사한 주문 당시 상품명 · 선택된 옵션
    private String productName;

    private String optionName;

    @Column(nullable = false)
    private int rating;

    @Column(nullable = false, length = MAX_CONTENT_LENGTH)
    private String content;

    // 수정 기한 = 구매확정 시각 + 30일
    @Column(nullable = false)
    private LocalDateTime editableUntil;

    @Column(nullable = false)
    private boolean isEdited;

    @Column(nullable = false)
    private boolean isDeleted;

    // 판매자 답글 (없으면 null)
    @Column(length = MAX_SELLER_REPLY_LENGTH)
    private String sellerReplyContent;

    private LocalDateTime sellerRepliedAt;

    @Column(nullable = false)
    private boolean isSellerReplyEdited;

    private SalesPostReview(Long salesPostId, Long purchaseId, Long writerMemberId, String writerNickname,
                            String productName, String optionName, int rating, String content,
                            LocalDateTime editableUntil) {
        this.salesPostId = salesPostId;
        this.purchaseId = purchaseId;
        this.writerMemberId = writerMemberId;
        this.writerNickname = writerNickname;
        this.productName = productName;
        this.optionName = optionName;
        this.rating = rating;
        this.content = content;
        this.editableUntil = editableUntil;
        this.isEdited = false;
        this.isDeleted = false;
        this.isSellerReplyEdited = false;
    }

    // 구매 기록으로 리뷰를 만든다. 작성 자격(본인 구매 · 구매확정 · 기한 · 중복)은 작성 UseCase가 먼저 확인한다
    public static SalesPostReview write(SalesPostPurchase purchase, Long salesPostId, String writerNickname,
                                        int rating, String content) {
        validateRating(rating);
        validateContent(content);
        return new SalesPostReview(salesPostId, purchase.getId(), purchase.getBuyerMemberId(), writerNickname,
                purchase.getProductName(), purchase.getOptionName(), rating, content,
                writableUntil(purchase.getConfirmedAt()));
    }

    // 구매확정 시각으로 작성 · 수정 기한을 계산한다
    public static LocalDateTime writableUntil(LocalDateTime confirmedAt) {
        return confirmedAt.plusDays(WRITABLE_DAYS);
    }

    private static void validateRating(int rating) {
        if (rating < MIN_RATING || rating > MAX_RATING) {
            throw new ServiceException(SalesPostErrorCode.REVIEW_INVALID_RATING);
        }
    }

    private static void validateContent(String content) {
        if (content == null || content.isBlank()
                || content.length() < MIN_CONTENT_LENGTH || content.length() > MAX_CONTENT_LENGTH) {
            throw new ServiceException(SalesPostErrorCode.REVIEW_INVALID_CONTENT);
        }
    }
}
