package com.cartmate.cartmate.boundedContext.productService.payback.out;

import com.cartmate.cartmate.boundedContext.productService.payback.domain.PaybackOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

// 잠금 조회 · 공동구매 ID 조회는 PaybackOrderQueryRepository(QueryDSL)
public interface PaybackOrderRepository extends JpaRepository<PaybackOrder, Long>, PaybackOrderQueryRepository {

    // 없으면 넣고 있으면 그대로 (MySQL 전용 네이티브 SQL — JPQL · QueryDSL에는 INSERT ... VALUES · ON DUPLICATE KEY가 없다)
    @Modifying
    @Query(value = "INSERT INTO product_payback_order (order_id) VALUES (:orderId) "
            + "ON DUPLICATE KEY UPDATE order_id = order_id", nativeQuery = true)
    void insertIfAbsent(@Param("orderId") Long orderId);

    List<PaybackOrder> findAllByIndividualGroupPurchaseId(Long individualGroupPurchaseId);
}
