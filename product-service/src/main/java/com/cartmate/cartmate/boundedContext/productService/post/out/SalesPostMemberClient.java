package com.cartmate.cartmate.boundedContext.productService.post.out;

// 댓글 작성자 닉네임을 회원 서버에서 가져온다.
// 회원 서버가 응답하지 않으면 InternalServerUnavailableException(503)이 그대로 올라가 댓글 작성이 실패한다
public interface SalesPostMemberClient {

    String findNickname(Long memberId);
}
