package com.cartmate.cartmate.boundedContext.etcService.delivery.out;

import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryItemCancelMemo;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DeliveryItemCancelMemoRepository extends JpaRepository<DeliveryItemCancelMemo, Long> {

    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT IGNORE INTO delivery_item_cancel_memo (purchase_type, order_id, order_item_id, created_at) "
            + "VALUES (:purchaseType, :orderId, :orderItemId, :now)", nativeQuery = true)
    int insertIfAbsent(@Param("purchaseType") String purchaseType,
                       @Param("orderId") Long orderId,
                       @Param("orderItemId") Long orderItemId,
                       @Param("now") LocalDateTime now);

    @Query("select m.orderItemId from DeliveryItemCancelMemo m "
            + "where m.purchaseType = :purchaseType and m.orderId = :orderId")
    List<Long> findOrderItemIds(@Param("purchaseType") PurchaseType purchaseType, @Param("orderId") Long orderId);
}
