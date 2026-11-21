package com.cartmate.cartmate.boundedContext.paymentService.wallet.app;

import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.ReferenceType;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.Wallet;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WalletTransactionType;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WalletWithdrawOrder;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WalletWithdrawOrder.WithdrawAccount;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WithdrawStatus;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.exception.WalletErrorCode;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.WalletRepository;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.WalletWithdrawOrderRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * 예치금 출금의 DB 처리 (트랜잭션은 WalletFacade). 이체(외부 호출)는 {@link WalletWithdrawTransferProcessor}가 트랜잭션 밖에서 한다.
 *
 * <ul>
 *   <li>신청: 잔액 조건부 차감(잔액 ≥ 금액일 때만) + 출금 행 PENDING + 원장 WITHDRAW. 같은 멱등 키는 처음 건을 돌려준다</li>
 *   <li>상태 변경은 조건부 UPDATE로 한 번만: 이긴 요청만 다음 일(잔액 복구 등)을 한다</li>
 *   <li>FAILED·CANCELED가 되는 순간에만 잔액을 돌려주고 원장 WITHDRAW_RESTORE를 남긴다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class WalletWithdrawUseCase {

    static final Set<WithdrawStatus> IN_PROGRESS = EnumSet.of(WithdrawStatus.PENDING, WithdrawStatus.PROCESSING);

    private final WalletWithdrawOrderRepository walletWithdrawOrderRepository;
    private final WalletRepository walletRepository;
    private final LedgerRecorder ledgerRecorder;

    // 출금 신청. 같은 멱등 키·지갑·금액이면 기존 건을 그대로 돌려준다 (잔액을 다시 빼지 않음)
    public WalletWithdrawOrder request(Long walletId, Long amount, String idempotencyKey, String transferId,
                                       WithdrawAccount account, LocalDateTime now) {
        if (walletId == null) {
            throw new ServiceException(WalletErrorCode.WALLET_ID_REQUIRED);
        }
        if (amount == null || amount <= 0) {
            throw new ServiceException(WalletErrorCode.WALLET_INVALID_AMOUNT);
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ServiceException(WalletErrorCode.WALLET_IDEMPOTENCY_KEY_REQUIRED);
        }

        Optional<WalletWithdrawOrder> existing = walletWithdrawOrderRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            WalletWithdrawOrder order = existing.get();
            if (!order.getWallet().getId().equals(walletId) || !order.getAmount().equals(amount)) {
                throw new ServiceException(WalletErrorCode.WALLET_IDEMPOTENCY_CONFLICT);
            }
            return order;
        }

        // 동시에 같은 키로 와서 둘 다 여기까지 와도, 출금 행 저장에서 멱등 키 유니크로 한쪽이 롤백된다 (차감도 함께)
        if (walletRepository.decreaseBalance(walletId, amount, now) == 0) {
            if (!walletRepository.existsById(walletId)) {
                throw new ServiceException(WalletErrorCode.WALLET_NOT_FOUND);
            }
            throw new ServiceException(WalletErrorCode.WALLET_INSUFFICIENT_BALANCE);
        }

        Wallet wallet = findWallet(walletId); // 차감 뒤 잔액 (원장의 거래 후 잔액)
        WalletWithdrawOrder order = walletWithdrawOrderRepository.saveAndFlush(
                WalletWithdrawOrder.request(wallet, idempotencyKey, amount, transferId, account));
        ledgerRecorder.record(wallet, WalletTransactionType.WITHDRAW, null,
                order.getId(), ReferenceType.WITHDRAW_ORDER, idempotencyKey, amount);
        return order;
    }

    // PENDING → PROCESSING. 이긴 요청만 true (그 요청만 이체를 보낸다). processedBy: 관리자가 시작하면 관리자 ID
    public boolean startProcessing(Long withdrawOrderId, Long processedBy, LocalDateTime now) {
        return walletWithdrawOrderRepository.startProcessing(withdrawOrderId, WithdrawStatus.PENDING,
                WithdrawStatus.PROCESSING, processedBy, now) == 1;
    }

    // PROCESSING → COMPLETED (돈이 나갔다)
    public boolean complete(Long withdrawOrderId, String message, Long processedBy, LocalDateTime now) {
        return walletWithdrawOrderRepository.finish(withdrawOrderId, EnumSet.of(WithdrawStatus.PROCESSING),
                WithdrawStatus.COMPLETED, message, processedBy, now) == 1;
    }

    // PENDING·PROCESSING → FAILED (돈이 나가지 않았다) + 잔액 복구
    public boolean fail(Long withdrawOrderId, String reason, Long processedBy, LocalDateTime now) {
        WalletWithdrawOrder order = findOrder(withdrawOrderId);
        boolean failed = walletWithdrawOrderRepository.finish(withdrawOrderId, IN_PROGRESS,
                WithdrawStatus.FAILED, reason, processedBy, now) == 1;
        if (failed) {
            restore(order, now);
        }
        return failed;
    }

    // 회원 취소: PENDING일 때만 (PROCESSING이면 돈이 나갔을 수 있다). 이미 취소됐으면 그대로 성공
    public void cancel(Long walletId, Long withdrawOrderId, LocalDateTime now) {
        WalletWithdrawOrder order = findOwnedOrder(walletId, withdrawOrderId);
        if (order.getStatus() == WithdrawStatus.CANCELED) {
            return;
        }
        int canceled = walletWithdrawOrderRepository.finish(withdrawOrderId, EnumSet.of(WithdrawStatus.PENDING),
                WithdrawStatus.CANCELED, null, null, now);
        if (canceled == 0) {
            throw new ServiceException(WalletErrorCode.WALLET_WITHDRAW_NOT_CANCELABLE);
        }
        restore(order, now);
    }

    // 결과를 오래 모르는 이체 → 관리자 확인으로 넘긴다 (스케줄러가 더는 조회하지 않음)
    public boolean requestReview(Long withdrawOrderId, LocalDateTime now) {
        return walletWithdrawOrderRepository.requestReview(withdrawOrderId, WithdrawStatus.PROCESSING, now) == 1;
    }

    public WalletWithdrawOrder findOrder(Long withdrawOrderId) {
        if (withdrawOrderId == null) {
            throw new ServiceException(WalletErrorCode.WALLET_WITHDRAW_ORDER_ID_REQUIRED);
        }
        return walletWithdrawOrderRepository.findById(withdrawOrderId)
                .orElseThrow(() -> new ServiceException(WalletErrorCode.WALLET_WITHDRAW_ORDER_NOT_FOUND));
    }

    public WalletWithdrawOrder findOwnedOrder(Long walletId, Long withdrawOrderId) {
        WalletWithdrawOrder order = findOrder(withdrawOrderId);
        if (!order.getWallet().getId().equals(walletId)) {
            throw new ServiceException(WalletErrorCode.WALLET_ACCESS_DENIED);
        }
        return order;
    }

    // 조건부 UPDATE에서 이긴 요청만 부른다 → 같은 출금 건은 한 번만 복구.
    // 거래 번호가 없는 건(2026-11 이전 방식)은 신청 때 잔액을 빼지 않았으므로 돌려주지 않는다
    private void restore(WalletWithdrawOrder order, LocalDateTime now) {
        if (order.getTransferId() == null) {
            return;
        }
        Long walletId = order.getWallet().getId();
        walletRepository.increaseBalance(walletId, order.getAmount(), now);
        Wallet wallet = findWallet(walletId);
        ledgerRecorder.record(wallet, WalletTransactionType.WITHDRAW_RESTORE, null,
                order.getId(), ReferenceType.WITHDRAW_ORDER, order.getIdempotencyKey() + ":restore", order.getAmount());
    }

    private Wallet findWallet(Long walletId) {
        return walletRepository.findById(walletId)
                .orElseThrow(() -> new ServiceException(WalletErrorCode.WALLET_NOT_FOUND));
    }
}
