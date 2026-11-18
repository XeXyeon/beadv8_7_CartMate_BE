package com.cartmate.cartmate.boundedContext.productService.post.out;

import java.time.LocalDateTime;
import java.util.List;

// 판매글 쪽에서 회원 서버에 묻는 것.
// 회원 서버가 응답하지 않으면 InternalServerUnavailableException(503)이 그대로 올라간다
public interface SalesPostMemberClient {

    // 댓글 · 리뷰 작성자 닉네임
    String findNickname(Long memberId);

    // from 이상 ~ to 미만에 탈퇴가 완료된 회원 ID. from이 null이면 처음부터 (첫 실행)
    // 회원 서버 API(/internal/members/withdrawn)는 팀원이 만들 예정 — 이 형식을 가정한다
    List<Long> findWithdrawnMemberIds(LocalDateTime from, LocalDateTime to);
}
