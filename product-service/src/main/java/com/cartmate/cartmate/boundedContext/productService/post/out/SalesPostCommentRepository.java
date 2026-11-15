package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SalesPostCommentRepository extends JpaRepository<SalesPostComment, Long> {

    // 목록에 보일 원댓글을 최신순으로. 삭제됐고 살아 있는 답글도 없는 원댓글은 목록에서 빠지므로 쿼리에서 미리 뺀다
    // (그래야 페이지마다 개수와 전체 개수가 맞는다)
    @Query(value = """
            select c from SalesPostComment c
            where c.salesPostId = :postId and c.parentId is null
              and (c.isDeleted = false
                   or exists (select 1 from SalesPostComment r where r.parentId = c.id and r.isDeleted = false))
            order by c.id desc
            """,
            countQuery = """
            select count(c) from SalesPostComment c
            where c.salesPostId = :postId and c.parentId is null
              and (c.isDeleted = false
                   or exists (select 1 from SalesPostComment r where r.parentId = c.id and r.isDeleted = false))
            """)
    Page<SalesPostComment> findVisibleRoots(@Param("postId") Long postId, Pageable pageable);

    // 원댓글들의 답글을 한 번에, 오래된 순으로
    List<SalesPostComment> findByParentIdInOrderByIdAsc(Collection<Long> parentIds);
}
