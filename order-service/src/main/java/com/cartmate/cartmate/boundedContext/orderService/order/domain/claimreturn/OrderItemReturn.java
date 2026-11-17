package com.cartmate.cartmate.boundedContext.orderService.order.domain.claimreturn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 승인된 반품 한 건의 기록. 클레임 ID가 유일해서 같은 승인 이벤트를 다시 받아도 한 번만 반영한다.
 */
@Entity
@Table(name = "order_item_return",
        uniqueConstraints = @UniqueConstraint(name = "uk_order_item_return_claim", columnNames = "claim_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItemReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    // 환불 금액. 쿠폰 조건 때문에 0원일 수 있다
    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "returned_at", nullable = false)
    private LocalDateTime returnedAt;

    private OrderItemReturn(Long claimId, Long orderId, long amount, LocalDateTime returnedAt) {
        this.claimId = claimId;
        this.orderId = orderId;
        this.amount = amount;
        this.returnedAt = returnedAt;
    }

    public static OrderItemReturn of(Long claimId, Long orderId, long amount, LocalDateTime returnedAt) {
        return new OrderItemReturn(claimId, orderId, amount, returnedAt);
    }
}
