package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostReview;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesPostReviewRepository extends JpaRepository<SalesPostReview, Long> {
}
