package com.cartmate.cartmate.boundedContext.orderService.order.out.itemcancel;

import com.cartmate.cartmate.boundedContext.orderService.order.domain.itemcancel.OrderItemCancel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemCancelRepository extends JpaRepository<OrderItemCancel, Long> {
}
