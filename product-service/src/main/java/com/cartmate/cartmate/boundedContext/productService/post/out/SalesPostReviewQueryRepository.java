package com.cartmate.cartmate.boundedContext.productService.post.out;

public interface SalesPostReviewQueryRepository {

    // 탈퇴한 회원이 쓴 리뷰의 작성자 닉네임을 한 번에 바꾼다 (별점 · 내용은 그대로). 바뀐 행 수를 돌려준다
    // 실행 뒤 영속성 컨텍스트를 비운다
    int anonymizeWriter(Long memberId, String nickname);
}
