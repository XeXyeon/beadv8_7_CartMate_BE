package com.cartmate.cartmate.boundedContext.paymentService.wallet.out;

import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WalletWithdrawOrder;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WithdrawStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

// 상태 변경은 모두 조건부 UPDATE: 바뀐 행 수가 1이면 이긴 요청, 0이면 이미 다른 요청이 바꿨다
public interface WalletWithdrawOrderRepository extends JpaRepository<WalletWithdrawOrder, Long> {

    Optional<WalletWithdrawOrder> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    Page<WalletWithdrawOrder> findByWalletId(Long walletId, Pageable pageable);

    // 관리자 목록: 회원 ID를 같이 보여 주므로 지갑을 함께 읽는다 (행마다 지갑 조회 안 함)
    @EntityGraph(attributePaths = "wallet")
    Page<WalletWithdrawOrder> findByStatus(WithdrawStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "wallet")
    @Query("select o from WalletWithdrawOrder o")
    Page<WalletWithdrawOrder> findAllWithWallet(Pageable pageable);

    // 탈퇴 확인: 진행 중(대기·처리 중) 출금 금액.
    // transfer_id가 없는 행은 2026-11 이전 방식(신청 때 잔액을 빼지 않음)이라 돌아올 돈이 없다 → 빼고 센다. 스케줄러 조회도 같다
    @Query("""
            select coalesce(sum(o.amount), 0) from WalletWithdrawOrder o
            where o.wallet.memberId = :memberId and o.status in :statuses and o.transferId is not null
            """)
    long sumAmountByMemberIdAndStatusIn(@Param("memberId") Long memberId,
                                        @Param("statuses") Collection<WithdrawStatus> statuses);

    // 스케줄러: 신청 직후 이체를 시작하지 못한 건 (서버가 그 사이 내려감 등)
    @Query("""
            select o.id from WalletWithdrawOrder o
            where o.status = :status and o.createdAt < :before and o.transferId is not null
            order by o.id
            """)
    List<Long> findIdsByStatusCreatedBefore(@Param("status") WithdrawStatus status,
                                            @Param("before") LocalDateTime before, Limit limit);

    // 스케줄러: 결과를 아직 모르는 이체 (관리자 확인으로 넘긴 건은 빼고)
    @Query("""
            select o.id from WalletWithdrawOrder o
            where o.status = :status and o.processingStartedAt < :before and o.reviewRequestedAt is null
              and o.transferId is not null
            order by o.id
            """)
    List<Long> findIdsByStatusProcessingBefore(@Param("status") WithdrawStatus status,
                                               @Param("before") LocalDateTime before, Limit limit);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WalletWithdrawOrder o
            set o.status = :to, o.processingStartedAt = :now, o.processedBy = :processedBy,
                o.updatedAt = :now, o.version = o.version + 1
            where o.id = :id and o.status = :from
            """)
    int startProcessing(@Param("id") Long id, @Param("from") WithdrawStatus from, @Param("to") WithdrawStatus to,
                        @Param("processedBy") Long processedBy, @Param("now") LocalDateTime now);

    // 완료·실패·취소: from 중 하나일 때만 끝낸다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WalletWithdrawOrder o
            set o.status = :to, o.finishedAt = :now, o.resultMessage = :message,
                o.processedBy = coalesce(:processedBy, o.processedBy), o.updatedAt = :now, o.version = o.version + 1
            where o.id = :id and o.status in :from
            """)
    int finish(@Param("id") Long id, @Param("from") Collection<WithdrawStatus> from, @Param("to") WithdrawStatus to,
               @Param("message") String message, @Param("processedBy") Long processedBy,
               @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WalletWithdrawOrder o
            set o.reviewRequestedAt = :now, o.updatedAt = :now, o.version = o.version + 1
            where o.id = :id and o.status = :status and o.reviewRequestedAt is null
            """)
    int requestReview(@Param("id") Long id, @Param("status") WithdrawStatus status, @Param("now") LocalDateTime now);
}
