package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostReview;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostReviewRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.function.Consumer;

/**
 * 리뷰의 판매자 답글 작성 · 수정 · 삭제 (리뷰마다 1개).
 * 그 판매글의 판매자(로그인 정보의 sellerId가 판매글 sellerId와 같음)만, 판매글이 보이는 상태일 때, 기한 없이 할 수 있다.
 * 구매자는 sellerId가 null이라, 다른 판매글의 판매자는 sellerId가 달라 거절된다.
 */
@Service
@RequiredArgsConstructor
public class SalesPostReviewSellerReplyUseCase {
    private final SalesPostReviewRepository salesPostReviewRepository;
    private final SalesPostRepository salesPostRepository;

    @Transactional
    public void write(Long reviewId, Long sellerId, String content, LocalDateTime now) {
        apply(reviewId, sellerId, review -> review.writeSellerReply(content, now));
    }

    @Transactional
    public void edit(Long reviewId, Long sellerId, String content) {
        apply(reviewId, sellerId, review -> review.editSellerReply(content));
    }

    @Transactional
    public void delete(Long reviewId, Long sellerId) {
        apply(reviewId, sellerId, SalesPostReview::deleteSellerReply);
    }

    private void apply(Long reviewId, Long sellerId, Consumer<SalesPostReview> change) {
        SalesPostReview review = salesPostReviewRepository.findById(reviewId)
                .orElseThrow(() -> new ServiceException(SalesPostErrorCode.REVIEW_NOT_FOUND));
        SalesPost post = salesPostRepository.findById(review.getSalesPostId())
                .filter(p -> p.getStatus().isPubliclyVisible())
                .orElseThrow(() -> new ServiceException(SalesPostErrorCode.POST_NOT_FOUND));
        if (sellerId == null || !sellerId.equals(post.getSellerId())) {
            throw new ServiceException(SalesPostErrorCode.REVIEW_SELLER_REPLY_DENIED);
        }
        change.accept(review);
        salesPostReviewRepository.save(review);
    }
}
