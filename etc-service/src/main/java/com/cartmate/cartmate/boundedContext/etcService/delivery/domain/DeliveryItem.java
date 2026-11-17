package com.cartmate.cartmate.boundedContext.etcService.delivery.domain;

import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "delivery_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class DeliveryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_id", nullable = false)
    private Delivery delivery;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(name = "product_name")
    private String productName;     //주문 시점의 상품명 스냅샷

    @Column(name = "unit_price", nullable = false)
    private Long unitPrice;         //주문 시점의 단가 스냅샷. 옵션 가격이 반영된 최종 단가이며 정산 금액 계산에 쓴다.

    @Column(name = "quantity", nullable = false)
    private Long quantity;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    // 칸 종류를 문자열로 정해 둔다. 정하지 않으면 MySQL enum 칸이 만들어져 사유를 추가할 때마다 칸을 바꿔야 한다.
    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason", columnDefinition = "varchar(30)")
    private DeliveryCancelReason cancelReason;

    public static DeliveryItem create(
            Delivery delivery, Long orderItemId, String productName, Long unitPrice, Long quantity
    ) {
        DeliveryItem item = new DeliveryItem(delivery, orderItemId, productName, unitPrice, quantity);
        delivery.addItem(item);
        return item;
    }

    public boolean isCanceled() {
        return canceledAt != null;
    }

    // 이미 취소된 상품은 처음 시각과 사유를 그대로 둔다. 같은 취소가 다시 와도 결과가 같게 하려는 것이다.
    // 배송 상태와 함께 바뀌어야 해서 Delivery를 거쳐서만 부른다.
    boolean cancel(DeliveryCancelReason reason, LocalDateTime canceledAt) {
        if (isCanceled()) {
            return false;
        }
        this.canceledAt = canceledAt;
        this.cancelReason = reason;
        return true;
    }

    private DeliveryItem(
            Delivery delivery,
            Long orderItemId,
            String productName,
            Long unitPrice,
            Long quantity
    ) {
        this.delivery = delivery;
        this.orderItemId = orderItemId;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
    }
}
