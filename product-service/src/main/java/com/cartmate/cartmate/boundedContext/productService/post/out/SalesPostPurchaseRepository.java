package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostPurchase;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

// 리뷰를 쓸 수 있는 구매 기록 조회는 SalesPostPurchaseQueryRepository(QueryDSL)
public interface SalesPostPurchaseRepository extends JpaRepository<SalesPostPurchase, Long>, SalesPostPurchaseQueryRepository {

    Optional<SalesPostPurchase> findByPurchaseTypeAndOrderItemId(PurchaseType purchaseType, Long orderItemId);

    // 이벤트 하나에 담긴 주문항목들을 한 번에 찾는다
    List<SalesPostPurchase> findByPurchaseTypeAndOrderItemIdIn(PurchaseType purchaseType, Collection<Long> orderItemIds);
}
