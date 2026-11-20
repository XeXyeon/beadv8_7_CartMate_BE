package com.cartmate.cartmate.boundedContext.orderService.order.domain.groupshared;

import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.boundedContext.orderService.order.exception.OrderErrorCode;
import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "shared_group_order_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SharedGroupOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shared_group_order_id", nullable = false)
    private SharedGroupOrder sharedGroupOrder;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Long inventoryId;

    private Long sellerId;

    @Column(nullable = false, length = 100)
    private String productName;

    @Column(length = 100)
    private String optionName;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private long unitPrice;

    private SharedGroupOrderItem(SharedGroupOrder sharedGroupOrder, SharedGroupOrder.OrderItemLine line) {
        if (line.quantity() < 1) {
            throw new ServiceException(OrderErrorCode.ORDER_INVALID_QUANTITY);
        }
        if (line.unitPrice() < 0) {
            throw new ServiceException(OrderErrorCode.ORDER_INVALID_PRICE);
        }
        this.sharedGroupOrder = sharedGroupOrder;
        this.productId = line.productId();
        this.inventoryId = line.inventoryId();
        this.sellerId = line.sellerId();
        this.productName = line.productName();
        this.optionName = line.optionName();
        this.quantity = line.quantity();
        this.unitPrice = line.unitPrice();
    }

    static SharedGroupOrderItem create(SharedGroupOrder sharedGroupOrder, SharedGroupOrder.OrderItemLine line) {
        return new SharedGroupOrderItem(sharedGroupOrder, line);
    }

    public long amount() {
        return unitPrice * quantity;
    }

    // 참여자들이 쓴 플랫폼 쿠폰 할인 중 이 줄의 몫. 공동 주문이 확정될 때 한 번 정한다(정산·취소 환불이 쓴다).
    // 쿠폰을 아무도 안 썼거나 이 컬럼이 생기기 전에 확정된 주문은 null이고 0으로 본다
    @Column(name = "platform_discount_amount")
    private Long platformDiscountAmount;

    void assignPlatformDiscount(long discount) {
        this.platformDiscountAmount = discount;
    }

    public long platformDiscountOrZero() {
        return platformDiscountAmount == null ? 0 : platformDiscountAmount;
    }

    // 이 줄을 취소할 때 돌려줄 금액(참여자들이 실제로 낸 몫)
    public long netAmount() {
        return amount() - platformDiscountOrZero();
    }

    private LocalDateTime canceledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason", columnDefinition = "varchar(30)")
    private DeliveryCancelReason cancelReason;

    // 이 항목을 환불한 취소 건. 환불 없이 취소되면(환불 0원) 비어 있다
    @Column(name = "cancel_id")
    private Long cancelId;

    public boolean isCanceled() {
        return canceledAt != null;
    }

    // 이미 취소된 항목은 처음 취소된 사유와 시각을 그대로 둔다
    void cancel(DeliveryCancelReason reason, LocalDateTime now) {
        if (canceledAt == null) {
            canceledAt = now;
            cancelReason = reason;
        }
    }

    void linkCancel(Long cancelId) {
        if (this.cancelId == null) {
            this.cancelId = cancelId;
        }
    }
}
