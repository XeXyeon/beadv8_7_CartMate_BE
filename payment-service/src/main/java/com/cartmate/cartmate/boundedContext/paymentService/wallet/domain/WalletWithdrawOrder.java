package com.cartmate.cartmate.boundedContext.paymentService.wallet.domain;

import com.cartmate.cartmate.boundedContext.paymentService.wallet.exception.WalletErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 예치금 출금 (회원 계좌로 송금). 신청할 때 잔액에서 빼고, 실패·취소되면 돌려준다.
 *
 * <p>상태는 {@link WithdrawStatus}. 상태 변경은 저장소의 조건부 UPDATE(where status = ?)로만 한다
 * → 회원 취소·관리자 처리·스케줄러가 동시에 와도 하나만 성공한다.
 *
 * <p>계좌는 신청할 때 회원 서버에서 받아 여기에 따로 보관한다(스냅샷). 신청 뒤 회원이 계좌를 바꿔도 이 건은 신청 때 계좌로 보낸다.
 * 계좌번호는 결제 서버 키로 잠가 둔다. 2026-11 이전 행(회원이 직접 완료 처리하던 때)은 계좌·거래 번호가 비어 있다.
 */
@Entity
@Table(name = "wallet_withdraw_orders",
        uniqueConstraints = @UniqueConstraint(name = "uk_wallet_withdraw_orders_transfer_id", columnNames = "transfer_id"),
        indexes = {
                @Index(name = "idx_wallet_withdraw_orders_wallet", columnList = "wallet_id, id"),
                @Index(name = "idx_wallet_withdraw_orders_status", columnList = "status, id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class WalletWithdrawOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private Long amount;

    // varchar로 고정 (PROCESSING 추가). 이미 MySQL enum인 DB는 한 번 ALTER
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private WithdrawStatus status;

    // 이체 거래 고유 번호 (20자). 은행 이체 API의 거래 번호로 그대로 쓴다 → 결과 조회·중복 방지
    @Column(name = "transfer_id", length = 20)
    private String transferId;

    @Column(name = "bank_code", length = 3)
    private String bankCode;

    @Column(name = "bank_name", length = 20)
    private String bankName;

    @Column(name = "account_holder", length = 50)
    private String accountHolder;

    @Column(name = "account_number_enc", length = 255)
    private String accountNumberEncrypted;

    @Column(name = "account_number_masked", length = 20)
    private String accountNumberMasked;

    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    // 완료: 관리자 송금 메모(이체 확인 번호 등) / 실패: 사유
    @Column(name = "result_message", length = 255)
    private String resultMessage;

    // 관리자가 처리했으면 관리자 회원 ID (자동 이체는 null)
    @Column(name = "processed_by")
    private Long processedBy;

    // 결과를 오래 모르는 이체: 자동으로 실패 처리하지 않고 관리자 확인으로 넘긴 시각
    @Column(name = "review_requested_at")
    private LocalDateTime reviewRequestedAt;

    @Version
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static WalletWithdrawOrder request(Wallet wallet, String idempotencyKey, Long amount, String transferId,
                                              WithdrawAccount account) {
        if (wallet == null) {
            throw new ServiceException(WalletErrorCode.WALLET_INTERNAL_WALLET_MISSING);
        }
        if (amount == null || amount <= 0) {
            throw new ServiceException(WalletErrorCode.WALLET_INVALID_AMOUNT);
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ServiceException(WalletErrorCode.WALLET_IDEMPOTENCY_KEY_REQUIRED);
        }

        WalletWithdrawOrder order = new WalletWithdrawOrder();
        order.wallet = wallet;
        order.idempotencyKey = idempotencyKey;
        order.amount = amount;
        order.status = WithdrawStatus.PENDING;
        order.transferId = transferId;
        order.bankCode = account.bankCode();
        order.bankName = account.bankName();
        order.accountHolder = account.accountHolder();
        order.accountNumberEncrypted = account.accountNumberEncrypted();
        order.accountNumberMasked = account.accountNumberMasked();
        return order;
    }

    // 이체 대상: 계좌 정보 (계좌번호는 잠긴 채로)
    public record WithdrawAccount(String bankCode, String bankName, String accountHolder,
                                  String accountNumberEncrypted, String accountNumberMasked) {
    }
}
