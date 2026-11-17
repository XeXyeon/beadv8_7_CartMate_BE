package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostReview;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostReviewHistory;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostReviewHistoryRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostReviewRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 리뷰 수정. 작성자 본인만, 수정 기한(구매확정 + 30일) 안에서, 판매글이 보이는 상태일 때.
 * 바뀌면 수정 전 별점 · 내용을 이력에 남기고, 판매글 별점 합계에 (새 별점 - 옛 별점)만큼 더한다 (리뷰 수는 그대로).
 */
@Service
@RequiredArgsConstructor
public class SalesPostReviewUpdateUseCase {
    private final SalesPostReviewRepository salesPostReviewRepository;
    private final SalesPostReviewHistoryRepository salesPostReviewHistoryRepository;
    private final SalesPostRepository salesPostRepository;

    @Transactional
    public void update(Long reviewId, Long memberId, int rating, String content, LocalDateTime now) {
        SalesPostReview review = salesPostReviewRepository.findById(reviewId)
                .orElseThrow(() -> new ServiceException(SalesPostErrorCode.REVIEW_NOT_FOUND));
        salesPostRepository.findById(review.getSalesPostId())
                .filter(p -> p.getStatus().isPubliclyVisible())
                .orElseThrow(() -> new ServiceException(SalesPostErrorCode.POST_NOT_FOUND));

        int oldRating = review.getRating();
        Optional<SalesPostReviewHistory> history = review.edit(memberId, rating, content, now);
        if (history.isEmpty()) {
            return;
        }
        salesPostReviewHistoryRepository.save(history.get());
        salesPostReviewRepository.saveAndFlush(review);
        if (rating != oldRating) {
            salesPostRepository.applyReviewSummary(review.getSalesPostId(), 0, rating - oldRating);
        }
    }
}
