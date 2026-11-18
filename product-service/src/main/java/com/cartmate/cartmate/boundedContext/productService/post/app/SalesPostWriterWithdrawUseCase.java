package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostCommentRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostReviewQueryRepository.ReviewSummaryByPost;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;

/**
 * 탈퇴가 완료된 회원들이 쓴 댓글 · 답글 · 리뷰를 삭제 처리한다(soft delete).
 * 화면 표시는 기존 규칙 그대로: 댓글은 뒤에 살아 있는 답글이 있으면 "삭제된 댓글입니다", 리뷰는 목록과 판매글 별점에서 빠진다.
 *
 * <p>탈퇴 완료 회원 ID는 매일 0시 스케줄러가 회원 서버에서 받아 넘긴다.
 * 같은 회원으로 다시 실행해도 이미 삭제된 글은 건너뛰어 결과가 같다.
 */
@Service
@RequiredArgsConstructor
public class SalesPostWriterWithdrawUseCase {
    private final SalesPostCommentRepository salesPostCommentRepository;
    private final SalesPostReviewRepository salesPostReviewRepository;
    private final SalesPostRepository salesPostRepository;

    // 삭제 처리한 댓글 · 리뷰 수
    @Transactional
    public Result deleteWrittenBy(Collection<Long> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            return new Result(0, 0);
        }
        int comments = salesPostCommentRepository.deleteWrittenBy(memberIds);

        // 리뷰는 판매글 집계(리뷰 수 · 별점 합계)에서 먼저 빼고 삭제한다 (일괄 삭제는 집계를 자동으로 바꾸지 않는다)
        for (ReviewSummaryByPost summary : salesPostReviewRepository.sumNotDeletedByWriters(memberIds)) {
            salesPostRepository.applyReviewSummary(summary.salesPostId(), -summary.reviewCount(), -summary.ratingSum());
        }
        int reviews = salesPostReviewRepository.deleteWrittenBy(memberIds);
        return new Result(comments, reviews);
    }

    public record Result(int deletedComments, int deletedReviews) {
    }
}
