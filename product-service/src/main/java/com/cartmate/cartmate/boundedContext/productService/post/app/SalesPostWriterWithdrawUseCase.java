package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostComment;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostCommentRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원이 탈퇴하면 그 회원이 쓴 댓글 · 답글 · 리뷰의 작성자를 "탈퇴한 회원"으로 바꾼다. 내용 · 별점은 남긴다.
 *
 * <p>아직 부르는 곳이 없다. member-service에 "탈퇴 완료" 처리가 없고(신청 · 취소만 있음),
 * 탈퇴 이벤트가 서버 간(outbox)으로 발행되지 않아 product-service가 받을 수 없기 때문이다.
 * 탈퇴 완료 이벤트가 생기면 리스너(SalesPostEventListener)에서 이 메서드를 부르면 된다.
 * (탈퇴 "신청"에 연결하면 30일 안에 취소한 회원의 닉네임을 되돌릴 수 없으니 연결하지 않는다)
 */
@Service
@RequiredArgsConstructor
public class SalesPostWriterWithdrawUseCase {
    private final SalesPostCommentRepository salesPostCommentRepository;
    private final SalesPostReviewRepository salesPostReviewRepository;

    // 바뀐 댓글 · 리뷰 수의 합을 돌려준다
    @Transactional
    public int anonymizeWriter(Long memberId) {
        int comments = salesPostCommentRepository.anonymizeWriter(memberId, SalesPostComment.WITHDRAWN_NICKNAME);
        int reviews = salesPostReviewRepository.anonymizeWriter(memberId, SalesPostComment.WITHDRAWN_NICKNAME);
        return comments + reviews;
    }
}
