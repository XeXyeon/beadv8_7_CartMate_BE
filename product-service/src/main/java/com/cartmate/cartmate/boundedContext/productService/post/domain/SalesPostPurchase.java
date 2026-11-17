package com.cartmate.cartmate.boundedContext.productService.post.domain;

import com.cartmate.cartmate.global.jpa.entity.BaseIdAndTime;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 리뷰 자격을 판단하는 구매 기록. 주문항목 하나가 한 줄이다.
 *
 * <ul>
 *   <li>구매 정보(구매자 · 상품 · 주문 당시 상품명 · 옵션명): 개인 주문은 결제 완료(OrderPaidEvent),
 *       공동 주문은 공동 주문 확정(SharedGroupOrderConfirmedEvent, 구매자 = 주문을 만든 회원)으로 채운다</li>
 *   <li>구매확정 시각: 구매확정(DeliveryConfirmedEvent)으로 채운다. 구매확정 전에 전액 환불된 상품은 이 이벤트에 오지 않는다</li>
 * </ul>
 * 두 이벤트의 도착 순서가 뒤바뀔 수 있어, 먼저 온 쪽이 줄을 만들고 나중에 온 쪽이 나머지 칸을 채운다.
 * 개인 주문과 공동 주문은 주문항목 id가 겹칠 수 있어 구매 유형 + 주문항목 id로 구분한다.
 */
@Entity
@Table(name = "POST_SALES_POST_PURCHASE",
        uniqueConstraints = @UniqueConstraint(name = "uk_sales_post_purchase_item",
                columnNames = {"purchase_type", "order_item_id"}),
        indexes = @Index(name = "idx_sales_post_purchase_buyer", columnList = "buyerMemberId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SalesPostPurchase extends BaseIdAndTime {

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private PurchaseType purchaseType;

    // 개인 주문이면 주문 id, 공동 주문이면 공동 주문 id
    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long orderItemId;

    // 아래 구매 정보는 구매확정 이벤트가 먼저 오면 잠시 비어 있다
    private Long buyerMemberId;

    private Long productId;

    private Long inventoryId;

    private Long sellerId;

    // 주문 당시 상품명 · 옵션명 (상품이 바뀌어도 리뷰에는 산 당시 이름을 보여준다)
    private String productName;

    private String optionName;

    // 구매확정 시각. 비어 있으면 아직 구매확정 전. 리뷰 작성 기한은 이 시각부터 계산한다
    private LocalDateTime confirmedAt;

    private SalesPostPurchase(PurchaseType purchaseType, Long orderId, Long orderItemId) {
        this.purchaseType = purchaseType;
        this.orderId = orderId;
        this.orderItemId = orderItemId;
    }

    // 결제 완료 · 공동 주문 확정 이벤트로 처음 만드는 경우
    public static SalesPostPurchase purchased(PurchaseType purchaseType, Long orderId, Long orderItemId,
                                              Long buyerMemberId, Long productId, Long inventoryId, Long sellerId,
                                              String productName, String optionName) {
        SalesPostPurchase purchase = new SalesPostPurchase(purchaseType, orderId, orderItemId);
        purchase.fillPurchase(buyerMemberId, productId, inventoryId, sellerId, productName, optionName);
        return purchase;
    }

    // 구매확정 이벤트가 먼저 와서 처음 만드는 경우
    public static SalesPostPurchase confirmedFirst(PurchaseType purchaseType, Long orderId, Long orderItemId,
                                                   LocalDateTime confirmedAt) {
        SalesPostPurchase purchase = new SalesPostPurchase(purchaseType, orderId, orderItemId);
        purchase.confirm(confirmedAt);
        return purchase;
    }

    // 구매 정보를 채운다. 이미 채워져 있으면(이벤트 중복) 그대로 둔다
    public void fillPurchase(Long buyerMemberId, Long productId, Long inventoryId, Long sellerId,
                             String productName, String optionName) {
        if (hasPurchase()) {
            return;
        }
        this.buyerMemberId = buyerMemberId;
        this.productId = productId;
        this.inventoryId = inventoryId;
        this.sellerId = sellerId;
        this.productName = productName;
        this.optionName = optionName;
    }

    // 구매확정 시각을 채운다. 이미 확정돼 있으면(이벤트 중복) 처음 시각을 유지한다
    public void confirm(LocalDateTime confirmedAt) {
        if (isConfirmed()) {
            return;
        }
        this.confirmedAt = confirmedAt;
    }

    public boolean hasPurchase() {
        return buyerMemberId != null;
    }

    public boolean isConfirmed() {
        return confirmedAt != null;
    }
}
