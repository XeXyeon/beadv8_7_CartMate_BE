package com.cartmate.cartmate.boundedContext.productService.payback.out;

import com.cartmate.cartmate.boundedContext.productService.payback.domain.IndividualGroupPurchasePayback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 잠금 조회는 IndividualGroupPurchasePaybackQueryRepository(QueryDSL)
public interface IndividualGroupPurchasePaybackRepository
        extends JpaRepository<IndividualGroupPurchasePayback, Long>, IndividualGroupPurchasePaybackQueryRepository {

    // 없으면 넣고 있으면 그대로 (MySQL 전용 네이티브 SQL — JPQL · QueryDSL에는 INSERT ... VALUES · ON DUPLICATE KEY가 없다)
    @Modifying
    @Query(value = "INSERT INTO product_individual_group_purchase_payback "
            + "(individual_group_purchase_id, opened, closed, revision, paid) VALUES (:id, false, false, 0, false) "
            + "ON DUPLICATE KEY UPDATE individual_group_purchase_id = individual_group_purchase_id",
            nativeQuery = true)
    void insertIfAbsent(@Param("id") Long individualGroupPurchaseId);
}
