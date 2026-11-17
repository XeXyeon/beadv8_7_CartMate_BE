package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostCommentHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesPostCommentHistoryRepository extends JpaRepository<SalesPostCommentHistory, Long> {
}
