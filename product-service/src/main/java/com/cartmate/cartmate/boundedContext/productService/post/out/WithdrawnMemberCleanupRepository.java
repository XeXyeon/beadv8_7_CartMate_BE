package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.WithdrawnMemberCleanup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WithdrawnMemberCleanupRepository extends JpaRepository<WithdrawnMemberCleanup, Long> {
}
