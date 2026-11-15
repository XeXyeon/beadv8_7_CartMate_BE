package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostPurchase;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SalesPostPurchaseRepository extends JpaRepository<SalesPostPurchase, Long> {

    Optional<SalesPostPurchase> findByPurchaseTypeAndOrderItemId(PurchaseType purchaseType, Long orderItemId);

    // 이벤트 하나에 담긴 주문항목들을 한 번에 찾는다
    List<SalesPostPurchase> findByPurchaseTypeAndOrderItemIdIn(PurchaseType purchaseType, Collection<Long> orderItemIds);

    // 리뷰를 쓸 수 있는 구매 기록: 이 회원이 구매자, 구매확정 시각이 since 이후(기한 안), 리뷰가 없음(삭제된 리뷰 포함).
    // 기한이 가까운(구매확정이 오래된) 순서
    @Query("""
            select p from SalesPostPurchase p
            where p.buyerMemberId = :memberId and p.confirmedAt is not null and p.confirmedAt >= :since
              and not exists (select 1 from SalesPostReview r where r.purchaseId = p.id)
            order by p.confirmedAt asc, p.id asc
            """)
    List<SalesPostPurchase> findWritable(@Param("memberId") Long memberId, @Param("since") LocalDateTime since);
}
