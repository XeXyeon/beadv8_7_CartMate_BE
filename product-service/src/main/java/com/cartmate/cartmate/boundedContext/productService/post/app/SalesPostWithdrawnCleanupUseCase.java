package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.post.domain.WithdrawnMemberCleanup;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostMemberClient;
import com.cartmate.cartmate.boundedContext.productService.post.out.WithdrawnMemberCleanupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 탈퇴 회원 댓글 · 리뷰 정리 한 번 실행.
 * <ol>
 *   <li>마지막 성공 시각(from)을 읽는다. 없으면 첫 실행이라 처음부터 받는다</li>
 *   <li>회원 서버에서 from ~ to(이번 실행 시각)에 탈퇴 완료된 회원 ID를 받는다 — 트랜잭션 밖</li>
 *   <li>한 트랜잭션에서 댓글 · 리뷰를 삭제 처리하고 마지막 성공 시각을 to로 저장한다</li>
 * </ol>
 * 2번이나 3번에서 실패하면 예외가 그대로 올라가고 마지막 성공 시각은 그대로라, 다음 실행이 그 기간까지 함께 받는다.
 */
@Service
@RequiredArgsConstructor
public class SalesPostWithdrawnCleanupUseCase {
    private final WithdrawnMemberCleanupRepository withdrawnMemberCleanupRepository;
    private final SalesPostMemberClient salesPostMemberClient;
    private final SalesPostWriterWithdrawUseCase salesPostWriterWithdrawUseCase;
    private final TransactionTemplate transactionTemplate;

    public Result run(LocalDateTime now) {
        LocalDateTime from = withdrawnMemberCleanupRepository.findById(WithdrawnMemberCleanup.SINGLE_ROW_ID)
                .map(WithdrawnMemberCleanup::getLastSucceededAt)
                .orElse(null);
        LocalDateTime to = now;

        List<Long> memberIds = salesPostMemberClient.findWithdrawnMemberIds(from, to);

        SalesPostWriterWithdrawUseCase.Result deleted = transactionTemplate.execute(status -> {
            SalesPostWriterWithdrawUseCase.Result result = salesPostWriterWithdrawUseCase.deleteWrittenBy(memberIds);
            markSucceeded(to, now);
            return result;
        });
        return new Result(from, to, memberIds.size(), deleted.deletedComments(), deleted.deletedReviews());
    }

    private void markSucceeded(LocalDateTime to, LocalDateTime now) {
        Optional<WithdrawnMemberCleanup> existing = withdrawnMemberCleanupRepository.findById(WithdrawnMemberCleanup.SINGLE_ROW_ID);
        if (existing.isPresent()) {
            existing.get().succeeded(to, now);
            withdrawnMemberCleanupRepository.save(existing.get());
        } else {
            withdrawnMemberCleanupRepository.save(WithdrawnMemberCleanup.firstSucceeded(to, now));
        }
    }

    // 한 번 실행한 결과 (로그용)
    public record Result(LocalDateTime from, LocalDateTime to, int withdrawnMembers,
                         int deletedComments, int deletedReviews) {
    }
}
