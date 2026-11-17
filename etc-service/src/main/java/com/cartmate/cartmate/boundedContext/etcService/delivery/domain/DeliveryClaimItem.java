package com.cartmate.cartmate.boundedContext.etcService.delivery.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "delivery_claim_item",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_delivery_claim_item",
                columnNames = {"claim_id", "delivery_item_id"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeliveryClaimItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "claim_id", nullable = false)
    private DeliveryClaim claim;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_item_id", nullable = false)
    private DeliveryItem deliveryItem;

    @Column(name = "quantity", nullable = false)
    private Long quantity;      //교환·환불을 요청한 수량

    @Enumerated(EnumType.STRING)
    @Column(name = "result", columnDefinition = "varchar(20)")
    private ClaimItemResult result;     //판매자 결정. 결정 전에는 비어 있다.

    @Column(name = "reject_reason", length = DeliveryClaim.DETAIL_MAX_LENGTH)
    private String rejectReason;        //거절한 상품의 사유

    public static DeliveryClaimItem create(DeliveryClaim claim, DeliveryItem deliveryItem, Long quantity) {
        return new DeliveryClaimItem(claim, deliveryItem, quantity);
    }

    // 판매자가 이 상품을 승인하거나 거절한다. 승인이면 사유를 남기지 않는다.
    public void decide(ClaimItemResult result, String rejectReason) {
        this.result = result;
        this.rejectReason = result == ClaimItemResult.REJECTED ? rejectReason : null;
    }

    private DeliveryClaimItem(DeliveryClaim claim, DeliveryItem deliveryItem, Long quantity) {
        this.claim = claim;
        this.deliveryItem = deliveryItem;
        this.quantity = quantity;
    }
}
