package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostReview;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SalesPostReviewResponse;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostReviewRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매글 리뷰 목록: 최신순 10개씩, 삭제된 리뷰는 빼고(판매자 답글도 함께 안 보인다). 로그인 없이 볼 수 있다.
 */
@Service
@RequiredArgsConstructor
public class SalesPostReviewSelectUseCase {
    public static final int PAGE_SIZE = 10;

    private final SalesPostRepository salesPostRepository;
    private final SalesPostReviewRepository salesPostReviewRepository;

    // viewerMemberId: 로그인했으면 회원 id (내 리뷰 표시용), 아니면 null
    @Transactional(readOnly = true)
    public PageResponse<SalesPostReviewResponse> selectReviews(Long postId, int page, Long viewerMemberId) {
        salesPostRepository.findById(postId)
                .filter(p -> p.getStatus().isPubliclyVisible())
                .orElseThrow(() -> new ServiceException(SalesPostErrorCode.POST_NOT_FOUND));

        return PageResponse.of(salesPostReviewRepository
                .findBySalesPostIdAndIsDeletedFalseOrderByIdDesc(postId, PageRequest.of(Math.max(page, 0), PAGE_SIZE))
                .map(review -> toResponse(review, viewerMemberId)));
    }

    private SalesPostReviewResponse toResponse(SalesPostReview review, Long viewerMemberId) {
        SalesPostReviewResponse.SellerReply sellerReply = review.getSellerReplyContent() == null
                ? null
                : new SalesPostReviewResponse.SellerReply(review.getSellerReplyContent(), review.getSellerRepliedAt(),
                        review.isSellerReplyEdited());
        return new SalesPostReviewResponse(
                review.getId(),
                review.getWriterNickname(),
                review.getProductName(),
                review.getOptionName(),
                review.getRating(),
                review.getContent(),
                review.isEdited(),
                viewerMemberId != null && viewerMemberId.equals(review.getWriterMemberId()),
                review.getCreateDate(),
                sellerReply);
    }
}
