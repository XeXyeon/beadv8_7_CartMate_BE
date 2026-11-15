package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostReview;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesPostReviewRepository extends JpaRepository<SalesPostReview, Long> {

    // 이 구매 기록(주문항목)으로 쓴 리뷰가 있는지 (삭제된 리뷰 포함)
    boolean existsByPurchaseId(Long purchaseId);
}
