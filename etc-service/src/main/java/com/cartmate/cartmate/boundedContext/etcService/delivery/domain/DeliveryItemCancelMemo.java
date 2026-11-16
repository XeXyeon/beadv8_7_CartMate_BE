package com.cartmate.cartmate.boundedContext.etcService.delivery.domain;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 주문 항목이 취소됐다는 메모. 배송이 아직 없을 때 취소가 먼저 오면, 나중에 온 배송 생성이 이 메모를 보고
 * 그 상품을 빼고 만든다. 배송 생성과 취소는 주문 단위 상태 줄({@link DeliveryOrderState})을 잠가 순서를 맞춘다.
 * 개인 주문 항목과 공동구매 항목은 ID를 따로 매겨 겹칠 수 있어 유니크 키에 구매 유형을 넣는다.
 */
@Entity
@Table(name = "delivery_item_cancel_memo",
        uniqueConstraints = @UniqueConstraint(name = "uk_delivery_item_cancel_memo",
                columnNames = {"purchase_type", "order_item_id"}),
        indexes = @Index(name = "idx_delivery_item_cancel_memo_order", columnList = "purchase_type, order_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeliveryItemCancelMemo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "purchase_type", nullable = false, columnDefinition = "varchar(20)")
    private PurchaseType purchaseType;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
