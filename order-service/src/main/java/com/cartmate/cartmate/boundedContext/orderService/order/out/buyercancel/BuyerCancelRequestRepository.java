package com.cartmate.cartmate.boundedContext.orderService.order.out.buyercancel;

import com.cartmate.cartmate.boundedContext.orderService.order.domain.buyercancel.BuyerCancelRequest;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.buyercancel.BuyerCancelRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BuyerCancelRequestRepository extends JpaRepository<BuyerCancelRequest, Long> {

    @Query("select distinct r from BuyerCancelRequest r join fetch r.items where r.orderId = :orderId order by r.id desc")
    List<BuyerCancelRequest> findWithItemsByOrderId(@Param("orderId") Long orderId);

    boolean existsByOrderIdAndStatus(Long orderId, BuyerCancelRequestStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from BuyerCancelRequest r where r.id = :id")
    Optional<BuyerCancelRequest> findByIdForUpdate(@Param("id") Long id);

    @Query("select r.id from BuyerCancelRequest r "
            + "where r.status = :status and r.lastSentAt <= :lastSentBefore order by r.id")
    List<Long> findIdsByStatusAndLastSentAtBefore(@Param("status") BuyerCancelRequestStatus status,
                                                  @Param("lastSentBefore") LocalDateTime lastSentBefore);
}
