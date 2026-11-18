package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSeller;
import com.cartmate.cartmate.boundedContext.productService.post.domain.WithdrawnMemberCleanup;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostMemberClient;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostSellerRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.WithdrawnMemberCleanupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 탈퇴 회원 정리 한 번 실행.
 * <ol>
 *   <li>마지막 성공 시각(from)을 읽는다. 없으면 첫 실행이라 처음부터 받는다</li>
 *   <li>회원 서버에서 from ~ to(이번 실행 시각)에 탈퇴 완료된 회원 ID를 받는다 — 트랜잭션 밖</li>
 *   <li>한 트랜잭션에서
 *     <ul>
 *       <li>그 회원들의 댓글 · 리뷰를 삭제 처리한다</li>
 *       <li>그 회원 중 판매자의 판매글을 기존 삭제 흐름으로 삭제한다(삭제 중으로 바꾸고 삭제 이벤트를 outbox에 저장).
 *           판매 중지는 탈퇴 신청 때 이미 됐다</li>
 *       <li>마지막 성공 시각을 to로 저장한다</li>
 *     </ul>
 *   </li>
 * </ol>
 * 2 · 3번에서 하나라도 실패하면(판매글 하나의 삭제를 시작하지 못한 경우 포함) 전체가 롤백되고 마지막 성공 시각은 그대로라,
 * 다음 실행이 같은 회원을 다시 처리한다. 이미 삭제된 글 · 삭제 중인 판매글은 건너뛰어 다시 처리해도 안전하다.
 */
@Service
@RequiredArgsConstructor
public class SalesPostWithdrawnCleanupUseCase {
    private final WithdrawnMemberCleanupRepository withdrawnMemberCleanupRepository;
    private final SalesPostMemberClient salesPostMemberClient;
    private final SalesPostWriterWithdrawUseCase salesPostWriterWithdrawUseCase;
    private final SalesPostSellerRepository salesPostSellerRepository;
    private final SalesPostRepository salesPostRepository;
    private final SalesPostDeleteUseCase salesPostDeleteUseCase;
    private final TransactionTemplate transactionTemplate;

    public Result run(LocalDateTime now) {
        LocalDateTime from = withdrawnMemberCleanupRepository.findById(WithdrawnMemberCleanup.SINGLE_ROW_ID)
                .map(WithdrawnMemberCleanup::getLastSucceededAt)
                .orElse(null);
        LocalDateTime to = now;

        List<Long> memberIds = salesPostMemberClient.findWithdrawnMemberIds(from, to);

        return transactionTemplate.execute(status -> {
            SalesPostWriterWithdrawUseCase.Result deleted = salesPostWriterWithdrawUseCase.deleteWrittenBy(memberIds);
            int deletedPosts = deletePostsOfSellers(memberIds);
            markSucceeded(to, now);
            return new Result(from, to, memberIds.size(),
                    deleted.deletedComments(), deleted.deletedReviews(), deletedPosts);
        });
    }

    // 탈퇴 완료 회원 중 판매자의 판매글을 삭제한다. 삭제를 시작한 판매글 수
    private int deletePostsOfSellers(List<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return 0;
        }
        List<Long> sellerIds = salesPostSellerRepository.findAllByMemberIdIn(memberIds).stream()
                .map(SalesPostSeller::getId)
                .toList();
        List<SalesPost> posts = salesPostRepository.findDeletableBySellerIds(sellerIds);
        for (SalesPost post : posts) {
            salesPostDeleteUseCase.deleteSalesPostByUser(post.getId(), post.getSellerId());
        }
        return posts.size();
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
                         int deletedComments, int deletedReviews, int deletedPosts) {
    }
}
