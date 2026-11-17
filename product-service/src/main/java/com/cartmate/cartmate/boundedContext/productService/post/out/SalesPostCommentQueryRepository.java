package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface SalesPostCommentQueryRepository {

    // 목록에 보일 원댓글을 최신순으로. 삭제됐고 살아 있는 답글도 없는 원댓글은 목록에서 빠지므로 쿼리에서 미리 뺀다
    // (그래야 페이지마다 개수와 전체 개수가 맞는다)
    Page<SalesPostComment> findVisibleRoots(Long postId, Pageable pageable);

    // 탈퇴한 회원이 쓴 댓글 · 답글의 작성자 닉네임을 한 번에 바꾼다 (내용은 그대로 둔다). 바뀐 행 수를 돌려준다
    // 실행 뒤 영속성 컨텍스트를 비운다
    int anonymizeWriter(Long memberId, String nickname);
}
