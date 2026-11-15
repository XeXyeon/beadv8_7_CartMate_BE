package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostReviewHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesPostReviewHistoryRepository extends JpaRepository<SalesPostReviewHistory, Long> {
}
