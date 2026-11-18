package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;

public interface SalesPostCommentQueryRepository {

    // 목록에 보일 원댓글을 최신순으로. 삭제됐고 살아 있는 답글도 없는 원댓글은 목록에서 빠지므로 쿼리에서 미리 뺀다
    // (그래야 페이지마다 개수와 전체 개수가 맞는다)
    Page<SalesPostComment> findVisibleRoots(Long postId, Pageable pageable);

    // 탈퇴한 회원들이 쓴 댓글 · 답글을 한 번에 삭제 표시한다(soft delete). 이미 삭제된 것은 건너뛴다. 바뀐 행 수
    // 실행 전 flush, 실행 뒤 영속성 컨텍스트를 비운다
    int deleteWrittenBy(Collection<Long> memberIds);
}
