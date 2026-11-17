package com.cartmate.cartmate.boundedContext.etcService.delivery.domain;

import com.cartmate.cartmate.boundedContext.etcService.delivery.exception.DeliveryErrorCode;
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
@Table(name = "delivery_claim")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class DeliveryClaim {

    public static final int DETAIL_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_id", nullable = false)
    private Delivery delivery;

    @Column(name = "member_id", nullable = false)
    private Long memberId;      //접수한 구매자

    @Enumerated(EnumType.STRING)
    @Column(name = "claim_type", nullable = false)
    private ClaimType claimType;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false)
    private ClaimReason reason;

    @Column(name = "detail", length = DETAIL_MAX_LENGTH)
    private String detail;      //상세 사유

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status;

    @Column(name = "reject_reason", length = DETAIL_MAX_LENGTH)
    private String rejectReason;    //판매자 거절 사유

    @Column(name = "not_accepted_code", length = 50)
    private String notAcceptedCode;    //접수 검사에서 거절된 이유 코드

    @Column(name = "processed_at")
    private LocalDateTime processedAt;  //판매자 승인·거절 시각

    @Column(name = "completed_at")
    private LocalDateTime completedAt;  //처리 완료 시각

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_status", nullable = false, columnDefinition = "varchar(20) not null default 'NONE'")
    private RefundStatus refundStatus = RefundStatus.NONE;

    // 같은 클레임의 승인·거절·완료가 분산락 없이 겹칠 때 늦게 커밋한 쪽을 실패시킨다.
    // 이미 있는 행은 컬럼이 추가될 때 0으로 채워진다(기본값). null이면 버전 비교가 맞지 않아 수정이 모두 실패한다.
    @Version
    @Column(name = "version", nullable = false, columnDefinition = "bigint not null default 0")
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;    //접수 시각

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static DeliveryClaim create(
            Delivery delivery, Long memberId, ClaimType claimType, ClaimReason reason, String detail
    ) {
        return new DeliveryClaim(delivery, memberId, claimType, reason, detail);
    }

    private DeliveryClaim(
            Delivery delivery, Long memberId, ClaimType claimType, ClaimReason reason, String detail
    ) {
        this.delivery = delivery;
        this.memberId = memberId;
        this.claimType = claimType;
        this.reason = reason;
        this.detail = detail;
        this.status = needsVerification() ? ClaimStatus.VERIFYING : ClaimStatus.REQUESTED;
    }

    // 구매자 사정 반품은 쿠폰 최소 금액이 깨져 환불이 음수가 될 수 있어, 주문 서버가 확인한 뒤에 접수한다.
    public boolean needsVerification() {
        return claimType == ClaimType.REFUND && !reason.isSellerFault();
    }

    // 접수 검사를 통과하면 접수로 바꾼다. 확인 중이 아니면(같은 결과를 다시 받음) 아무것도 하지 않는다.
    public boolean accept() {
        if (this.status != ClaimStatus.VERIFYING) {
            return false;
        }
        this.status = ClaimStatus.REQUESTED;
        return true;
    }

    // 접수 검사에서 거절되면 접수 불가로 끝낸다. 확인 중이 아니면 아무것도 하지 않는다.
    public boolean notAccept(String code) {
        if (this.status != ClaimStatus.VERIFYING) {
            return false;
        }
        this.status = ClaimStatus.NOT_ACCEPTED;
        this.notAcceptedCode = code;
        return true;
    }

    // 판매자가 상품별 결정을 한 번에 확정한다. 하나라도 승인하면 승인, 모두 거절하면 거절이다.
    // 반품은 승인한 상품이 있으면 환불을 기다리고, 교환이나 전부 거절이면 환불이 필요 없다.
    public void decide(boolean anyApproved) {
        if (this.status != ClaimStatus.REQUESTED) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_INVALID_STATUS);
        }
        this.status = anyApproved ? ClaimStatus.APPROVED : ClaimStatus.REJECTED;
        this.refundStatus = claimType == ClaimType.REFUND && anyApproved ? RefundStatus.PENDING : RefundStatus.NONE;
        this.processedAt = LocalDateTime.now();
    }

    // 판매자가 접수된 교환·환불을 수락한다. 이미 수락된 클레임이면 아무것도 하지 않아 재요청해도 안전하다.
    public void approve(boolean refundRequired) {
        if (this.status == ClaimStatus.APPROVED) {
            return;
        }
        if (this.status != ClaimStatus.REQUESTED) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_INVALID_STATUS);
        }

        this.status = ClaimStatus.APPROVED;
        this.refundStatus = refundRequired ? RefundStatus.PENDING : RefundStatus.NONE;
        this.processedAt = LocalDateTime.now();
    }

    // 판매자가 접수된 교환·환불을 거절하고 사유를 남긴다. 이미 거절된 클레임이면 아무것도 하지 않아 재요청해도 안전하다.
    public void reject(String rejectReason) {
        if (this.status == ClaimStatus.REJECTED) {
            return;
        }
        if (this.status != ClaimStatus.REQUESTED) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_INVALID_STATUS);
        }

        this.status = ClaimStatus.REJECTED;
        this.rejectReason = rejectReason;
        this.processedAt = LocalDateTime.now();
    }

    // 수락된 교환·환불을 처리 완료로 바꾸고 완료 시각을 기록한다. 이미 완료된 클레임이면 아무것도 하지 않아 재요청해도 안전하다.
    public void complete() {
        if (this.status == ClaimStatus.COMPLETED) {
            return;
        }
        if (this.status != ClaimStatus.APPROVED) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_INVALID_STATUS);
        }
        if (this.refundStatus == RefundStatus.PENDING) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_REFUND_PENDING);
        }
        if (this.refundStatus == RefundStatus.FAILED) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_REFUND_FAILED);
        }

        this.status = ClaimStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    public void completeRefund() {
        if (this.refundStatus == RefundStatus.NONE) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_REFUND_NOT_REQUIRED);
        }
        this.refundStatus = RefundStatus.COMPLETED;
    }

    public void failRefund() {
        if (this.refundStatus == RefundStatus.NONE) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_REFUND_NOT_REQUIRED);
        }
        if (this.refundStatus == RefundStatus.COMPLETED) {
            return;
        }
        this.refundStatus = RefundStatus.FAILED;
    }
}
