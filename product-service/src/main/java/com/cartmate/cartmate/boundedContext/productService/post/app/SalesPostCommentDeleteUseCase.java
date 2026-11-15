package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostComment;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostCommentRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원댓글 · 답글 삭제 (soft delete). 작성자 본인만, 판매 종료된 판매글에서도 삭제할 수 있다.
 * "삭제된 댓글입니다"로 남길지 목록에서 뺄지는 목록 조회(SalesPostCommentSelectUseCase)가 정한다.
 */
@Service
@RequiredArgsConstructor
public class SalesPostCommentDeleteUseCase {
    private final SalesPostRepository salesPostRepository;
    private final SalesPostCommentRepository salesPostCommentRepository;

    @Transactional
    public void delete(Long postId, Long commentId, Long memberId) {
        salesPostRepository.findById(postId)
                .filter(p -> p.getStatus().isPubliclyVisible())
                .orElseThrow(() -> new ServiceException(SalesPostErrorCode.POST_NOT_FOUND));
        SalesPostComment comment = salesPostCommentRepository.findById(commentId)
                .filter(c -> c.getSalesPostId().equals(postId))
                .orElseThrow(() -> new ServiceException(SalesPostErrorCode.COMMENT_NOT_FOUND));

        comment.delete(memberId);
    }
}
