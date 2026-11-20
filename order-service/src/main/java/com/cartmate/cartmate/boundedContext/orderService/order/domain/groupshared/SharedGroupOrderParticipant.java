package com.cartmate.cartmate.boundedContext.orderService.order.domain.groupshared;

import com.cartmate.cartmate.shared.order.SharedGroupOrderParticipantStatus;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.boundedContext.orderService.order.exception.OrderErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(
        name = "shared_group_order_participant",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_shared_group_order_participant",
                columnNames = {"shared_group_order_id", "member_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SharedGroupOrderParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shared_group_order_id", nullable = false)
    private SharedGroupOrder sharedGroupOrder;

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private long shareAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    private SharedGroupOrderParticipantStatus status;

    private Long paymentId;

    private LocalDateTime paidAt;

    @Version
    private Long version;

    // 이 참여자가 분담금에 쓴 쿠폰(플랫폼 쿠폰만). 결제 진행 때 먼저 기록하고(쿠폰 사용 처리 전), 결제가 실패하면 지운다.
    // 결제가 끝나지 못한 채 공동 주문이 취소되면 이 기록으로 쿠폰을 돌려준다
    @Column(name = "member_coupon_id")
    private Long memberCouponId;

    // 분담금에서 뺀 쿠폰 할인. 쿠폰을 안 썼거나 이 컬럼이 생기기 전 참여자는 null이고 0으로 본다
    @Column(name = "discount_amount")
    private Long discountAmount;

    // 쿠폰은 한 번만 돌려준다. null이면 아직 돌려주지 않음
    @Column(name = "coupon_returned")
    private Boolean couponReturned;

    private SharedGroupOrderParticipant(SharedGroupOrder sharedGroupOrder, Long memberId, long shareAmount) {
        if (shareAmount < 0) {
            throw new ServiceException(OrderErrorCode.ORDER_INVALID_SHARE_AMOUNT);
        }
        this.sharedGroupOrder = sharedGroupOrder;
        this.memberId = memberId;
        this.shareAmount = shareAmount;
        this.status = SharedGroupOrderParticipantStatus.UNPAID;
    }

    static SharedGroupOrderParticipant create(SharedGroupOrder sharedGroupOrder, Long memberId, long shareAmount) {
        return new SharedGroupOrderParticipant(sharedGroupOrder, memberId, shareAmount);
    }

    public boolean is(Long memberId) {
        return this.memberId.equals(memberId);
    }

    public boolean isPaid() {
        return status == SharedGroupOrderParticipantStatus.PAID;
    }

    public boolean isPaidWith(Long paymentId) {
        return isPaid() && paymentId != null && paymentId.equals(this.paymentId);
    }

    // 실제로 낼 금액 = 분담금 - 쿠폰 할인
    public long payAmount() {
        return shareAmount - discountOrZero();
    }

    public long discountOrZero() {
        return discountAmount == null ? 0 : discountAmount;
    }

    // 결제 진행 때 쓸 쿠폰을 기록한다(없으면 null). 앞선 시도가 다른 쿠폰을 기록해 뒀으면 그 쿠폰을 반환한다(복구 대상)
    Long recordCoupon(Long memberCouponId) {
        if (isPaid()) {
            throw new ServiceException(OrderErrorCode.ORDER_ALREADY_PAID);
        }
        Long previous = this.memberCouponId;
        this.memberCouponId = memberCouponId;
        return previous != null && !previous.equals(memberCouponId) ? previous : null;
    }

    // 결제가 실패해 기록한 쿠폰을 돌려줄 때 기록을 지우고 그 쿠폰 ID를 반환한다. 이미 결제했거나 기록이 없으면 null
    Long releaseCoupon() {
        if (isPaid() || memberCouponId == null) {
            return null;
        }
        Long released = memberCouponId;
        this.memberCouponId = null;
        this.discountAmount = null;
        return released;
    }

    // 상품 서버에서 사용 처리된 쿠폰의 할인을 분담금에 반영한다(결제 반영 직전)
    void applyCoupon(Long memberCouponId, long discount) {
        if (isPaid()) {
            throw new ServiceException(OrderErrorCode.ORDER_ALREADY_PAID);
        }
        if (this.memberCouponId != null && !this.memberCouponId.equals(memberCouponId)) {
            throw new ServiceException(OrderErrorCode.ORDER_COUPON_ALREADY_APPLIED);
        }
        if (discount < 0 || discount > shareAmount) {
            throw new ServiceException(OrderErrorCode.ORDER_INVALID_DISCOUNT);
        }
        this.memberCouponId = memberCouponId;
        this.discountAmount = discount;
    }

    // 공동 주문이 취소돼 돌려줄 쿠폰이 있는지
    boolean couponRestorable() {
        return memberCouponId != null && !Boolean.TRUE.equals(couponReturned);
    }

    void markCouponReturned() {
        this.couponReturned = true;
    }

    void markPaid(Long paymentId, LocalDateTime paidAt) {
        if (status != SharedGroupOrderParticipantStatus.UNPAID) {
            throw new ServiceException(OrderErrorCode.ORDER_ALREADY_PAID);
        }
        this.status = SharedGroupOrderParticipantStatus.PAID;
        this.paymentId = paymentId;
        this.paidAt = paidAt;
    }
}
