package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostPurchase;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SalesPostPurchaseRepository extends JpaRepository<SalesPostPurchase, Long> {

    Optional<SalesPostPurchase> findByPurchaseTypeAndOrderItemId(PurchaseType purchaseType, Long orderItemId);

    // 이벤트 하나에 담긴 주문항목들을 한 번에 찾는다
    List<SalesPostPurchase> findByPurchaseTypeAndOrderItemIdIn(PurchaseType purchaseType, Collection<Long> orderItemIds);
}
