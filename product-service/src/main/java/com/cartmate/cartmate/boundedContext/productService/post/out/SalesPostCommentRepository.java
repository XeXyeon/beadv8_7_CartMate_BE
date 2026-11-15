package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostComment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesPostCommentRepository extends JpaRepository<SalesPostComment, Long> {
}
