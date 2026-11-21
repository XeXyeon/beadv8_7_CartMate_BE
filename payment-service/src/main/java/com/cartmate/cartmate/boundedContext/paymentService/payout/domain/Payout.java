package com.cartmate.cartmate.boundedContext.paymentService.payout.domain;

import com.cartmate.cartmate.boundedContext.paymentService.payout.exception.PayoutErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "payout",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_payout_purchase_type_order_item", columnNames = {"purchase_type", "order_item_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Payout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    // 주문상품이 개인 주문인지 공동물품 주문인지. 이 컬럼이 생기기 전에 만든 정산은 null이다.
    @Enumerated(EnumType.STRING)
    @Column(name = "purchase_type")
    private PayoutPurchaseType purchaseType;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    // 주문 시점의 상품명 스냅샷. 이 컬럼이 생기기 전에 만든 정산은 null이다.
    @Column(name = "product_name")
    private String productName;

    // 정산 대상 수량. 구매확정 전에 환불된 수량은 뺀 값이다. 이 컬럼이 생기기 전에 만든 정산은 null이다.
    @Column(name = "quantity")
    private Long quantity;

    // 주문 시각. 이 컬럼이 생기기 전에 만든 정산은 null이다.
    @Column(name = "ordered_at")
    private LocalDateTime orderedAt;

    // 판매 금액 = 단가 x 수량 - 판매자 부담 쿠폰 할인. 수수료의 기준이다.
    @Column(name = "sales_amount", nullable = false)
    private Long salesAmount;

    // 판매자 부담 쿠폰 할인(판매 금액에서 이미 뺐다). 이 컬럼이 생기기 전에 만든 정산은 null이고 0으로 본다.
    @Column(name = "seller_discount_amount")
    private Long sellerDiscountAmount;

    // 판매 금액 중 플랫폼 부담 쿠폰 할인. 구매자가 내지 않아 홀딩에 없고, 지급 때 플랫폼 지갑이 낸다.
    // 이 컬럼이 생기기 전에 만든 정산은 null이고 0으로 본다.
    @Column(name = "platform_discount_amount")
    private Long platformDiscountAmount;

    @Column(name = "fee_amount", nullable = false)
    private Long feeAmount;

    @Column(name = "payout_amount", nullable = false)
    private Long payoutAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PayoutStatus status;

    @Column(name = "confirmed_at", nullable = false)
    private LocalDateTime confirmedAt;

    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    // 분산락이 뚫려 같은 정산을 동시에 지급할 때의 마지막 안전망. 배치는 다음 실행에서 다시 시도한다.
    // 이미 있는 행은 컬럼이 추가될 때 0으로 채워진다(기본값). null이면 버전 비교가 맞지 않아 수정이 모두 실패한다.
    @Version
    @Column(name = "version", nullable = false, columnDefinition = "bigint not null default 0")
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;


    public static Payout create(Long sellerId, Long orderId, PayoutPurchaseType purchaseType, Long orderItemId,
                                String productName, Long quantity, Long salesAmount, LocalDateTime orderedAt,
                                LocalDateTime confirmedAt) {
        return create(sellerId, orderId, purchaseType, orderItemId, productName, quantity, salesAmount, 0L, 0L,
                orderedAt, confirmedAt);
    }

    // grossAmount는 단가 x 수량(할인 전)이다. 판매자 부담 할인을 빼서 판매 금액을 정하고, 수수료는 그 판매 금액에 매긴다.
    // 플랫폼 부담 할인은 판매 금액에 그대로 남는다(판매자는 정가로 받고, 할인분은 플랫폼이 낸다).
    public static Payout create(Long sellerId, Long orderId, PayoutPurchaseType purchaseType, Long orderItemId,
                                String productName, Long quantity, Long grossAmount, Long sellerDiscountAmount,
                                Long platformDiscountAmount, LocalDateTime orderedAt, LocalDateTime confirmedAt) {
        if (sellerId == null || orderId == null || purchaseType == null || orderItemId == null
                || confirmedAt == null) {
            throw new ServiceException(PayoutErrorCode.PAYOUT_REQUIRED_VALUE_MISSING);
        }

        long sellerDiscount = sellerDiscountAmount == null ? 0L : sellerDiscountAmount;
        long platformDiscount = platformDiscountAmount == null ? 0L : platformDiscountAmount;

        if (grossAmount == null || grossAmount < 0 || sellerDiscount < 0 || platformDiscount < 0
                || sellerDiscount + platformDiscount > grossAmount) {
            throw new ServiceException(PayoutErrorCode.PAYOUT_INVALID_AMOUNT);
        }

        return new Payout(sellerId, orderId, purchaseType, orderItemId, productName, quantity,
                grossAmount - sellerDiscount, sellerDiscount, platformDiscount, orderedAt, confirmedAt);
    }

    public long platformDiscountOrZero() {
        return platformDiscountAmount == null ? 0L : platformDiscountAmount;
    }

    // 대기 중인 정산을 지급 완료로 바꾸고 지급 시각을 기록한다.
    public void complete(LocalDateTime now) {
        if (status != PayoutStatus.PENDING) {
            throw new ServiceException(PayoutErrorCode.PAYOUT_NOT_PENDING);
        }

        this.status = PayoutStatus.COMPLETED;
        this.paidAt = now;
    }

    private Payout(Long sellerId, Long orderId, PayoutPurchaseType purchaseType, Long orderItemId, String productName,
                   Long quantity, Long salesAmount, Long sellerDiscountAmount, Long platformDiscountAmount,
                   LocalDateTime orderedAt, LocalDateTime confirmedAt) {
        this.sellerId = sellerId;
        this.orderId = orderId;
        this.purchaseType = purchaseType;
        this.orderItemId = orderItemId;
        this.productName = productName;
        this.quantity = quantity;
        this.orderedAt = orderedAt;
        this.salesAmount = salesAmount;
        this.sellerDiscountAmount = sellerDiscountAmount;
        this.platformDiscountAmount = platformDiscountAmount;
        this.feeAmount = PayoutPolicy.feeOf(salesAmount);
        this.payoutAmount = salesAmount - this.feeAmount;
        this.confirmedAt = confirmedAt;
        this.scheduledAt = PayoutPolicy.scheduledAtFor(confirmedAt);
        this.status = PayoutStatus.PENDING;
    }
}
