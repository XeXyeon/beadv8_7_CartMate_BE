package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostPurchase;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostReview;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostReviewResponse;
import com.cartmate.cartmate.boundedContext.productService.post.dto.WritableSalesPostReviewResponse;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostPurchaseRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostReviewRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 리뷰 작성과 "리뷰를 쓸 수 있는 상품 목록".
 * 작성 자격은 product-service 안의 구매 기록만 보고 판단한다 (작성할 때 다른 서버를 부르지 않는다. 닉네임은 Facade에서 미리 받는다).
 */
@Service
@RequiredArgsConstructor
public class SalesPostReviewCreateUseCase {
    private final SalesPostPurchaseRepository salesPostPurchaseRepository;
    private final SalesPostReviewRepository salesPostReviewRepository;
    private final SalesPostProductRepository salesPostProductRepository;
    private final SalesPostRepository salesPostRepository;

    @Transactional
    public CreateSalesPostReviewResponse create(Long memberId, String nickname, Long purchaseId, int rating,
                                                String content, LocalDateTime now) {
        // 1. 구매 기록이 없거나 남의 것이면 같은 "없음" (다른 사람의 구매 여부가 드러나지 않게)
        SalesPostPurchase purchase = salesPostPurchaseRepository.findById(purchaseId == null ? -1L : purchaseId)
                .filter(SalesPostPurchase::hasPurchase)
                .filter(p -> p.getBuyerMemberId().equals(memberId))
                .orElseThrow(() -> new ServiceException(SalesPostErrorCode.REVIEW_PURCHASE_NOT_FOUND));
        // 2. 구매확정 후, 3. 기한(구매확정 + 30일) 안
        if (!purchase.isConfirmed()) {
            throw new ServiceException(SalesPostErrorCode.REVIEW_NOT_CONFIRMED);
        }
        if (now.isAfter(SalesPostReview.writableUntil(purchase.getConfirmedAt()))) {
            throw new ServiceException(SalesPostErrorCode.REVIEW_PERIOD_EXPIRED);
        }
        // 4. 주문항목마다 1개 (삭제한 리뷰가 있어도 다시 쓸 수 없다)
        if (salesPostReviewRepository.existsByPurchaseId(purchase.getId())) {
            throw new ServiceException(SalesPostErrorCode.REVIEW_ALREADY_WRITTEN);
        }
        // 5. 상품이 속한 판매글이 다른 사용자에게 보이는 상태(판매 중 · 판매 중지 · 판매 종료)
        SalesPost post = findVisiblePostOfProduct(purchase.getProductId());

        SalesPostReview review;
        try {
            review = salesPostReviewRepository.saveAndFlush(
                    SalesPostReview.write(purchase, post.getId(), nickname, rating, content));
        } catch (DataIntegrityViolationException e) {
            // 같은 주문항목으로 동시에 두 번 쓴 경우 (유니크 키)
            throw new ServiceException(SalesPostErrorCode.REVIEW_ALREADY_WRITTEN);
        }
        salesPostRepository.applyReviewSummary(post.getId(), 1, rating);
        return new CreateSalesPostReviewResponse(review.getId(), post.getId());
    }

    // 리뷰를 쓸 수 있는 상품: 구매확정됐고 기한이 남았고 아직 리뷰가 없으며, 판매글이 보이는 상태인 것만
    @Transactional(readOnly = true)
    public List<WritableSalesPostReviewResponse> selectWritable(Long memberId, LocalDateTime now) {
        LocalDateTime since = now.minusDays(SalesPostReview.WRITABLE_DAYS);
        List<SalesPostPurchase> purchases = salesPostPurchaseRepository.findWritable(memberId, since);
        if (purchases.isEmpty()) {
            return List.of();
        }
        Map<Long, SalesPost> postByProductId = salesPostProductRepository.findByIdIn(
                        purchases.stream().map(SalesPostPurchase::getProductId).distinct().toList()).stream()
                .collect(Collectors.toMap(SalesPostProduct::getId, SalesPostProduct::getSalesPost));

        return purchases.stream()
                .map(p -> {
                    SalesPost post = postByProductId.get(p.getProductId());
                    if (post == null || !post.getStatus().isPubliclyVisible()) {
                        return null;
                    }
                    return new WritableSalesPostReviewResponse(p.getId(), post.getId(), p.getProductName(),
                            p.getOptionName(), p.getConfirmedAt(), SalesPostReview.writableUntil(p.getConfirmedAt()));
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private SalesPost findVisiblePostOfProduct(Long productId) {
        return salesPostProductRepository.findById(productId)
                .map(SalesPostProduct::getSalesPost)
                .filter(p -> p.getStatus().isPubliclyVisible())
                .orElseThrow(() -> new ServiceException(SalesPostErrorCode.POST_NOT_FOUND));
    }
}
