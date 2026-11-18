package com.cartmate.cartmate.boundedContext.productService.post.domain;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Optional;

// 판매글 댓글. 원댓글과 답글을 한 테이블에 둔다.
// parentId가 비어 있으면 원댓글, 값이 있으면 그 원댓글의 답글이다 (답글에 답글은 달 수 없다).
@Entity
@Table(name = "POST_SALES_POST_COMMENT",
        indexes = {
                @Index(name = "idx_sales_post_comment_post_parent", columnList = "salesPostId, parentId"),
                @Index(name = "idx_sales_post_comment_parent", columnList = "parentId"),
                @Index(name = "idx_sales_post_comment_writer", columnList = "writerMemberId")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SalesPostComment extends BaseIdAndTime {

    public static final int MAX_CONTENT_LENGTH = 500;

    @Column(nullable = false)
    private Long salesPostId;

    // 원댓글이면 null, 답글이면 원댓글 id
    private Long parentId;

    @Column(nullable = false)
    private Long writerMemberId;

    // 작성 당시 닉네임. 닉네임을 바꿔도 따라 바뀌지 않고, 탈퇴하면 "탈퇴한 회원"으로 바뀐다
    @Column(nullable = false, length = 50)
    private String writerNickname;

    @Column(nullable = false, length = MAX_CONTENT_LENGTH)
    private String content;

    // 비밀 댓글. 답글은 원댓글의 값을 그대로 따른다
    @Column(nullable = false)
    private boolean isSecret;

    // 판매글의 판매자가 쓴 답글인지 ("판매자" 표시)
    @Column(nullable = false)
    private boolean isSellerReply;

    // 한 번이라도 수정됐는지 ("(수정됨)" 표시)
    @Column(nullable = false)
    private boolean isEdited;

    // soft delete. 실제로 지우지 않고 표시만 한다
    @Column(nullable = false)
    private boolean isDeleted;

    private SalesPostComment(Long salesPostId, Long parentId, Long writerMemberId, String writerNickname,
                             String content, boolean isSecret, boolean isSellerReply) {
        this.salesPostId = salesPostId;
        this.parentId = parentId;
        this.writerMemberId = writerMemberId;
        this.writerNickname = writerNickname;
        this.content = content;
        this.isSecret = isSecret;
        this.isSellerReply = isSellerReply;
        this.isEdited = false;
        this.isDeleted = false;
    }

    // 원댓글
    public static SalesPostComment root(Long salesPostId, Long writerMemberId, String writerNickname,
                                        String content, boolean isSecret) {
        validateContent(content);
        return new SalesPostComment(salesPostId, null, writerMemberId, writerNickname, content, isSecret, false);
    }

    // 답글. 판매글과 비밀 여부는 원댓글을 따른다. 답글에는 답글을 달 수 없고, 삭제된 댓글에도 달 수 없다
    public static SalesPostComment reply(SalesPostComment parent, Long writerMemberId, String writerNickname,
                                         String content, boolean isSellerReply) {
        if (!parent.isRoot()) {
            throw new ServiceException(SalesPostErrorCode.COMMENT_REPLY_TO_REPLY);
        }
        if (parent.isDeleted()) {
            throw new ServiceException(SalesPostErrorCode.COMMENT_NOT_FOUND);
        }
        validateContent(content);
        return new SalesPostComment(parent.getSalesPostId(), parent.getId(), writerMemberId, writerNickname,
                content, parent.isSecret(), isSellerReply);
    }

    // 작성자 본인만 내용을 수정한다 (비밀 여부는 바꿀 수 없다).
    // 내용이 그대로면 아무것도 바꾸지 않고, 바뀌면 수정 전 내용을 담은 이력을 돌려준다 (저장은 호출한 쪽에서)
    public Optional<SalesPostCommentHistory> edit(Long memberId, String newContent) {
        if (isDeleted) {
            throw new ServiceException(SalesPostErrorCode.COMMENT_NOT_FOUND);
        }
        if (!isWrittenBy(memberId)) {
            throw new ServiceException(SalesPostErrorCode.COMMENT_EDIT_DENIED);
        }
        validateContent(newContent);
        if (newContent.equals(content)) {
            return Optional.empty();
        }
        SalesPostCommentHistory history = new SalesPostCommentHistory(getId(), content);
        this.content = newContent;
        this.isEdited = true;
        return Optional.of(history);
    }

    // 작성자 본인만 삭제한다. 실제로 지우지 않고 표시만 한다 (답글은 그대로 남는다)
    public void delete(Long memberId) {
        if (isDeleted) {
            throw new ServiceException(SalesPostErrorCode.COMMENT_NOT_FOUND);
        }
        if (!isWrittenBy(memberId)) {
            throw new ServiceException(SalesPostErrorCode.COMMENT_DELETE_DENIED);
        }
        this.isDeleted = true;
    }

    public boolean isWrittenBy(Long memberId) {
        return memberId != null && memberId.equals(writerMemberId);
    }

    private static void validateContent(String content) {
        if (content == null || content.isBlank() || content.length() > MAX_CONTENT_LENGTH) {
            throw new ServiceException(SalesPostErrorCode.COMMENT_INVALID_CONTENT);
        }
    }

    public boolean isRoot() {
        return parentId == null;
    }
}
