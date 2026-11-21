package com.cartmate.cartmate.boundedContext.paymentService.wallet.out;

import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByMemberId(Long memberId);

    boolean existsByMemberId(Long MemberId);

    // 잔액이 충분할 때만 뺀다 (동시에 와도 음수가 되지 않음). version도 올려 엔티티로 고치는 다른 처리와 충돌을 알린다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Wallet w set w.balance = w.balance - :amount, w.version = w.version + 1, w.updatedAt = :now
            where w.id = :id and w.balance >= :amount
            """)
    int decreaseBalance(@Param("id") Long id, @Param("amount") Long amount, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Wallet w set w.balance = w.balance + :amount, w.version = w.version + 1, w.updatedAt = :now
            where w.id = :id
            """)
    int increaseBalance(@Param("id") Long id, @Param("amount") Long amount, @Param("now") LocalDateTime now);
}
