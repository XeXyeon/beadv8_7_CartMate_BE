package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostComment;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostCommentHistoryRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostCommentRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원댓글 · 답글 수정. 작성자 본인만, 언제든 수정할 수 있고 수정 전 내용은 이력으로 남긴다.
 * 판매 종료된 판매글에서도 기존 글은 수정할 수 있다 (다른 사용자에게 보이는 판매글이면 된다).
 */
@Service
@RequiredArgsConstructor
public class SalesPostCommentUpdateUseCase {
    private final SalesPostRepository salesPostRepository;
    private final SalesPostCommentRepository salesPostCommentRepository;
    private final SalesPostCommentHistoryRepository salesPostCommentHistoryRepository;

    @Transactional
    public void update(Long postId, Long commentId, Long memberId, String content) {
        salesPostRepository.findById(postId)
                .filter(p -> p.getStatus().isPubliclyVisible())
                .orElseThrow(() -> new ServiceException(SalesPostErrorCode.POST_NOT_FOUND));
        SalesPostComment comment = salesPostCommentRepository.findById(commentId)
                .filter(c -> c.getSalesPostId().equals(postId))
                .orElseThrow(() -> new ServiceException(SalesPostErrorCode.COMMENT_NOT_FOUND));

        comment.edit(memberId, content).ifPresent(salesPostCommentHistoryRepository::save);
    }
}
