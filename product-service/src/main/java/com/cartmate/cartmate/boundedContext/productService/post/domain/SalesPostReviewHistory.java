package com.cartmate.cartmate.boundedContext.productService.post.domain;

import com.cartmate.cartmate.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 리뷰 수정 이력. 수정할 때마다 수정 전 별점 · 내용을 한 줄씩 쌓는다. 수정 시각은 이 행의 생성 시각(createDate)이다.
@Entity
@Table(name = "POST_SALES_POST_REVIEW_HISTORY",
        indexes = @Index(name = "idx_sales_post_review_history_review", columnList = "reviewId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SalesPostReviewHistory extends BaseIdAndTime {

    @Column(nullable = false)
    private Long reviewId;

    @Column(nullable = false)
    private int beforeRating;

    @Column(nullable = false, length = SalesPostReview.MAX_CONTENT_LENGTH)
    private String beforeContent;

    public SalesPostReviewHistory(Long reviewId, int beforeRating, String beforeContent) {
        this.reviewId = reviewId;
        this.beforeRating = beforeRating;
        this.beforeContent = beforeContent;
    }
}
