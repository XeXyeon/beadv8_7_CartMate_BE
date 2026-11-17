package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostComment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

// 목록에 보일 원댓글 페이지 · 탈퇴 회원 일괄 변경은 SalesPostCommentQueryRepository(QueryDSL)
public interface SalesPostCommentRepository extends JpaRepository<SalesPostComment, Long>, SalesPostCommentQueryRepository {

    // 원댓글들의 답글을 한 번에, 오래된 순으로
    List<SalesPostComment> findByParentIdInOrderByIdAsc(Collection<Long> parentIds);
}
