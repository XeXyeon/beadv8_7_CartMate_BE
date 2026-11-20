package com.cartmate.cartmate.boundedContext.orderService.order.domain.personal;

import com.cartmate.cartmate.shared.order.OrderType;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.Order.OrderLine;
import com.cartmate.cartmate.boundedContext.orderService.order.exception.OrderErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(
        name = "order_item",
        indexes = @Index(name = "idx_order_item_order_id", columnList = "order_id"),
        uniqueConstraints = @UniqueConstraint(
                name = OrderItem.PENDING_BUYER_INVENTORY_UNIQUE,
                columnNames = {"pending_buyer_member_id", "inventory_id"})
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    public static final String PENDING_BUYER_INVENTORY_UNIQUE = "uk_order_item_pending_buyer_inventory";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

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
    private long unitPrice;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "pending_buyer_member_id")
    private Long pendingBuyerMemberId;

    private OrderItem(Order order, OrderLine line) {
        if (line.quantity() < 1) {
            throw new ServiceException(OrderErrorCode.ORDER_INVALID_QUANTITY);
        }
        if (line.unitPrice() < 0) {
            throw new ServiceException(OrderErrorCode.ORDER_INVALID_PRICE);
        }
        this.order = order;
        this.productId = line.productId();
        this.inventoryId = line.inventoryId();
        this.sellerId = line.sellerId();
        this.productName = line.productName();
        this.optionName = line.optionName();
        this.unitPrice = line.unitPrice();
        this.quantity = line.quantity();
        this.pendingBuyerMemberId = order.getOrderType() == OrderType.PERSONAL ? order.getBuyerMemberId() : null;
    }

    static OrderItem create(Order order, OrderLine line) {
        return new OrderItem(order, line);
    }

    void releasePending() {
        this.pendingBuyerMemberId = null;
    }

    public long amount() {
        return unitPrice * quantity;
    }

    // 결제 때 이 줄에 고정 배분된 쿠폰 할인(줄 전체 수량 기준). 결제 뒤에는 바뀌지 않는다.
    // 쿠폰이 없거나 이 컬럼이 생기기 전에 결제된 주문은 null이다(그런 주문은 Order가 같은 규칙으로 그때 계산한다).
    @Column(name = "seller_discount_amount")
    private Long sellerDiscountAmount;      // 판매자 부담 할인

    @Column(name = "platform_discount_amount")
    private Long platformDiscountAmount;    // 플랫폼 부담 할인

    void assignDiscount(long sellerDiscount, long platformDiscount) {
        this.sellerDiscountAmount = sellerDiscount;
        this.platformDiscountAmount = platformDiscount;
    }

    boolean hasAssignedDiscount() {
        return sellerDiscountAmount != null || platformDiscountAmount != null;
    }

    long assignedDiscount() {
        return (sellerDiscountAmount == null ? 0 : sellerDiscountAmount)
                + (platformDiscountAmount == null ? 0 : platformDiscountAmount);
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

    // 반품이 승인돼 돌려보낸 시각. 반품은 상품 한 줄을 수량 전부 돌려보내는 것이라 취소처럼 시각만 남긴다
    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    public boolean isReturned() {
        return returnedAt != null;
    }

    // 취소됐거나 반품돼 주문에서 이미 빠진 항목
    public boolean isGone() {
        return isCanceled() || isReturned();
    }

    // 이미 반품된 항목은 처음 시각을 그대로 둔다
    void markReturned(LocalDateTime now) {
        if (returnedAt == null) {
            returnedAt = now;
        }
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
