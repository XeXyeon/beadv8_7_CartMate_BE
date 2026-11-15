package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostComment;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostCommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원이 탈퇴하면 그 회원의 댓글 · 답글 작성자를 "탈퇴한 회원"으로 바꾼다. 댓글 내용은 남긴다.
 *
 * <p>아직 부르는 곳이 없다. member-service에 "탈퇴 완료" 처리가 없고(신청 · 취소만 있음),
 * 탈퇴 이벤트가 서버 간(outbox)으로 발행되지 않아 product-service가 받을 수 없기 때문이다.
 * 탈퇴 완료 이벤트가 생기면 리스너에서 이 메서드를 부르면 된다.
 * (탈퇴 "신청"에 연결하면 30일 안에 취소한 회원의 닉네임을 되돌릴 수 없으니 연결하지 않는다)
 */
@Service
@RequiredArgsConstructor
public class SalesPostCommentWriterWithdrawUseCase {
    private final SalesPostCommentRepository salesPostCommentRepository;

    @Transactional
    public int anonymizeWriter(Long memberId) {
        return salesPostCommentRepository.anonymizeWriter(memberId, SalesPostComment.WITHDRAWN_NICKNAME);
    }
}
