package com.cartmate.cartmate.boundedContext.orderService.order.domain.itemcancel;

import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 주문 항목 취소가 확정된 한 건. 환불 이벤트에 이 ID를 실어 결제 서버가 건마다 한 번 환불한다.
 * 같은 판매자 몫이 여러 번 나눠 취소될 수 있어 판매자 단위가 아니라 건 단위로 남긴다.
 */
@Entity
@Table(name = "order_item_cancel",
        indexes = @Index(name = "idx_order_item_cancel_order", columnList = "purchase_type, order_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItemCancel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "purchase_type", nullable = false, columnDefinition = "varchar(20)")
    private PurchaseType purchaseType;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason", nullable = false, columnDefinition = "varchar(30)")
    private DeliveryCancelReason cancelReason;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "canceled_at", nullable = false)
    private LocalDateTime canceledAt;

    private OrderItemCancel(PurchaseType purchaseType, Long orderId, Long sellerId,
                            DeliveryCancelReason cancelReason, long amount, LocalDateTime canceledAt) {
        this.purchaseType = purchaseType;
        this.orderId = orderId;
        this.sellerId = sellerId;
        this.cancelReason = cancelReason;
        this.amount = amount;
        this.canceledAt = canceledAt;
    }

    public static OrderItemCancel of(PurchaseType purchaseType, Long orderId, Long sellerId,
                                     DeliveryCancelReason cancelReason, long amount, LocalDateTime canceledAt) {
        return new OrderItemCancel(purchaseType, orderId, sellerId, cancelReason, amount, canceledAt);
    }
}
