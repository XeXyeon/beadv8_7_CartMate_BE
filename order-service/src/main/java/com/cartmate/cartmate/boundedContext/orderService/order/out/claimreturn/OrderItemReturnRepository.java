package com.cartmate.cartmate.boundedContext.orderService.order.out.claimreturn;

import com.cartmate.cartmate.boundedContext.orderService.order.domain.claimreturn.OrderItemReturn;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemReturnRepository extends JpaRepository<OrderItemReturn, Long> {

    boolean existsByClaimId(Long claimId);
}
