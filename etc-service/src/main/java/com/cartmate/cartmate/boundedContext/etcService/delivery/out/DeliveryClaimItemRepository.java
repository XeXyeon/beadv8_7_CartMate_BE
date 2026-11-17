package com.cartmate.cartmate.boundedContext.etcService.delivery.out;

import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimItemResult;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimStatus;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimType;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryClaimItem;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface DeliveryClaimItemRepository extends JpaRepository<DeliveryClaimItem, Long> {

    // 지정한 상태의 클레임에 이미 포함된 배송상품이 하나라도 있는지 확인한다.
    @Query("select count(ci) > 0 from DeliveryClaimItem ci "
            + "where ci.deliveryItem.id in :deliveryItemIds and ci.claim.status in :statuses")
    boolean existsByDeliveryItemIdsAndClaimStatuses(
            @Param("deliveryItemIds") Collection<Long> deliveryItemIds,
            @Param("statuses") Collection<ClaimStatus> statuses);

    // 배송에서 지정한 유형·상태의 클레임으로 처리된 수량을 배송상품별로 합산한다. 클레임이 없는 배송상품은 결과에 나오지 않는다.
    @Query("select ci.deliveryItem.id as deliveryItemId, sum(ci.quantity) as quantity from DeliveryClaimItem ci "
            + "where ci.claim.delivery.id = :deliveryId and ci.claim.claimType = :claimType and ci.claim.status = :status "
            + "group by ci.deliveryItem.id")
    List<ClaimedQuantity> sumQuantityByDeliveryItem(
            @Param("deliveryId") Long deliveryId,
            @Param("claimType") ClaimType claimType,
            @Param("status") ClaimStatus status);

    // 승인·처리 완료된 클레임에서 거절되지 않은 상품의 배송상품 ID. 이 상품은 이미 돌려보냈다.
    // 결과가 비어 있는 상품도 센다: 승인·처리 완료 상태에서 결과가 비는 경우는 상품별 결정 이전의 승인뿐이라 승인으로 본다.
    @Query("select distinct ci.deliveryItem.id from DeliveryClaimItem ci "
            + "where ci.claim.delivery.id = :deliveryId and ci.claim.claimType = :claimType "
            + "and ci.claim.status in :statuses and (ci.result is null or ci.result = :approved)")
    List<Long> findApprovedReturnedDeliveryItemIds(
            @Param("deliveryId") Long deliveryId,
            @Param("claimType") ClaimType claimType,
            @Param("statuses") Collection<ClaimStatus> statuses,
            @Param("approved") ClaimItemResult approved);

    // 같은 주문에서 처리 중인 반품 클레임의 상품을 클레임 번호 순서로 조회한다. 거절된 상품은 빼고, 결과가 없는 상품은 승인 전으로 센다.
    // 반품 접수 검사가 이 반품들이 먼저 환불된다고 보고 계산하도록 이벤트에 싣는다.
    @Query("select ci from DeliveryClaimItem ci join fetch ci.claim c join fetch ci.deliveryItem di "
            + "where c.delivery.orderId = :orderId and c.delivery.purchaseType = :purchaseType "
            + "and c.claimType = :claimType and c.status in :statuses "
            + "and (ci.result is null or ci.result = :approved) "
            + "order by c.id, ci.id")
    List<DeliveryClaimItem> findPendingReturnItems(
            @Param("orderId") Long orderId,
            @Param("purchaseType") PurchaseType purchaseType,
            @Param("claimType") ClaimType claimType,
            @Param("statuses") Collection<ClaimStatus> statuses,
            @Param("approved") ClaimItemResult approved);

    // 배송상품별 클레임 수량 합계.
    interface ClaimedQuantity {

        Long getDeliveryItemId();

        Long getQuantity();
    }

    // 여러 클레임의 상품을 배송상품과 함께 한 번에 조회한다. 상품마다 배송상품을 따로 조회하는 N+1을 막는다.
    @Query("select ci from DeliveryClaimItem ci join fetch ci.deliveryItem "
            + "where ci.claim.id in :claimIds order by ci.id")
    List<DeliveryClaimItem> findAllWithDeliveryItemByClaimIds(@Param("claimIds") Collection<Long> claimIds);
}
