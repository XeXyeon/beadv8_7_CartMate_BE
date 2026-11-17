package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostComment;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostCommentRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원댓글 · 답글 작성.
 * 닉네임은 회원 서버 호출이라 트랜잭션 밖(SalesPostFacade)에서 미리 받아 넘긴다.
 */
@Service
@RequiredArgsConstructor
public class SalesPostCommentCreateUseCase {
    private final SalesPostRepository salesPostRepository;
    private final SalesPostCommentRepository salesPostCommentRepository;

    @Transactional
    public Long createRoot(Long postId, Long memberId, String nickname, String content, boolean isSecret) {
        findWritablePost(postId);
        SalesPostComment comment = SalesPostComment.root(postId, memberId, nickname, content, isSecret);
        return salesPostCommentRepository.save(comment).getId();
    }

    // 답글은 그 판매글의 판매자(sellerId 일치)나 원댓글 작성자(memberId 일치)만 쓸 수 있다.
    // 구매자는 sellerId가 null이라 판매자로 판정되지 않고, 다른 판매글의 판매자도 sellerId가 달라 일반 사용자로 본다
    @Transactional
    public Long createReply(Long postId, Long parentId, Long memberId, Long sellerId, String nickname, String content) {
        SalesPost post = findWritablePost(postId);
        SalesPostComment parent = salesPostCommentRepository.findById(parentId)
                .filter(c -> c.getSalesPostId().equals(postId))
                .orElseThrow(() -> new ServiceException(SalesPostErrorCode.COMMENT_NOT_FOUND));
        if (!parent.isRoot()) {
            throw new ServiceException(SalesPostErrorCode.COMMENT_REPLY_TO_REPLY);
        }

        boolean isSeller = sellerId != null && sellerId.equals(post.getSellerId());
        boolean isParentWriter = memberId.equals(parent.getWriterMemberId());
        if (!isSeller && !isParentWriter) {
            throw new ServiceException(SalesPostErrorCode.COMMENT_REPLY_DENIED);
        }

        SalesPostComment reply = SalesPostComment.reply(parent, memberId, nickname, content, isSeller);
        return salesPostCommentRepository.save(reply).getId();
    }

    // 다른 사용자에게 보이지 않는 판매글은 없는 글로, 보이지만 새로 쓸 수 없는 글(판매 종료)은 작성 불가로 응답한다
    private SalesPost findWritablePost(Long postId) {
        SalesPost post = salesPostRepository.findById(postId)
                .filter(p -> p.getStatus().isPubliclyVisible())
                .orElseThrow(() -> new ServiceException(SalesPostErrorCode.POST_NOT_FOUND));
        if (!post.getStatus().isCommentWritable()) {
            throw new ServiceException(SalesPostErrorCode.COMMENT_POST_NOT_WRITABLE);
        }
        return post;
    }
}
