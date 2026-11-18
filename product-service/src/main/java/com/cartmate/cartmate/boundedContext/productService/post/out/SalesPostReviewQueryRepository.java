package com.cartmate.cartmate.boundedContext.productService.post.out;

import java.util.Collection;
import java.util.List;

public interface SalesPostReviewQueryRepository {

    // 이 회원들이 쓴, 아직 삭제되지 않은 리뷰를 판매글별로 센다 (판매글 리뷰 집계에서 뺄 값)
    List<ReviewSummaryByPost> sumNotDeletedByWriters(Collection<Long> memberIds);

    // 이 회원들이 쓴 리뷰를 한 번에 삭제 표시한다(soft delete). 이미 삭제된 것은 건너뛴다. 바뀐 행 수
    // 실행 전 flush, 실행 뒤 영속성 컨텍스트를 비운다
    int deleteWrittenBy(Collection<Long> memberIds);

    // 판매글 하나의 리뷰 수 · 별점 합계
    record ReviewSummaryByPost(Long salesPostId, long reviewCount, long ratingSum) {
    }
}
