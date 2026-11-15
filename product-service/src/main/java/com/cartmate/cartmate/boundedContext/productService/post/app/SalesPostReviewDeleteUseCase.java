package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostReview;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostReviewRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리뷰 삭제 (soft delete). 작성자 본인만, 기한 없이 언제든, 판매글이 보이는 상태일 때.
 * 판매글 리뷰 수 -1, 별점 합계 -별점. 줄이 남아 같은 주문항목으로 다시 쓸 수 없다.
 */
@Service
@RequiredArgsConstructor
public class SalesPostReviewDeleteUseCase {
    private final SalesPostReviewRepository salesPostReviewRepository;
    private final SalesPostRepository salesPostRepository;

    @Transactional
    public void delete(Long reviewId, Long memberId) {
        SalesPostReview review = salesPostReviewRepository.findById(reviewId)
                .orElseThrow(() -> new ServiceException(SalesPostErrorCode.REVIEW_NOT_FOUND));
        salesPostRepository.findById(review.getSalesPostId())
                .filter(p -> p.getStatus().isPubliclyVisible())
                .orElseThrow(() -> new ServiceException(SalesPostErrorCode.POST_NOT_FOUND));

        review.delete(memberId);
        salesPostReviewRepository.saveAndFlush(review);
        salesPostRepository.applyReviewSummary(review.getSalesPostId(), -1, -review.getRating());
    }
}
