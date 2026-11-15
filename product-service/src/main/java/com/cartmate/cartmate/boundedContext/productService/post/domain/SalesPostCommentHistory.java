package com.cartmate.cartmate.boundedContext.productService.post.domain;

import com.cartmate.cartmate.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 댓글 수정 이력. 수정할 때마다 수정 전 내용을 한 줄씩 쌓는다 (분쟁 시 확인용).
// 수정 시각은 이 행의 생성 시각(createDate)이다.
@Entity
@Table(name = "POST_SALES_POST_COMMENT_HISTORY",
        indexes = @Index(name = "idx_sales_post_comment_history_comment", columnList = "commentId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SalesPostCommentHistory extends BaseIdAndTime {

    @Column(nullable = false)
    private Long commentId;

    @Column(nullable = false, length = SalesPostComment.MAX_CONTENT_LENGTH)
    private String beforeContent;

    public SalesPostCommentHistory(Long commentId, String beforeContent) {
        this.commentId = commentId;
        this.beforeContent = beforeContent;
    }
}
