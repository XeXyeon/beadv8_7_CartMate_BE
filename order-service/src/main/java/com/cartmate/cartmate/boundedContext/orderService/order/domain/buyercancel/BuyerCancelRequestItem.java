package com.cartmate.cartmate.boundedContext.orderService.order.domain.buyercancel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "buyer_cancel_request_item",
        indexes = @Index(name = "idx_buyer_cancel_request_item_request", columnList = "request_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BuyerCancelRequestItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private BuyerCancelRequest request;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", columnDefinition = "varchar(20)")
    private BuyerCancelItemResult result;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    private BuyerCancelRequestItem(BuyerCancelRequest request, Long orderItemId, Long sellerId) {
        this.request = request;
        this.orderItemId = orderItemId;
        this.sellerId = sellerId;
    }

    static BuyerCancelRequestItem create(BuyerCancelRequest request, Long orderItemId, Long sellerId) {
        return new BuyerCancelRequestItem(request, orderItemId, sellerId);
    }
}
