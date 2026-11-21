package com.cartmate.cartmate.boundedContext.paymentService.wallet.app;

import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.*;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.exception.WalletErrorCode;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.HoldingRepository;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.HoldingSettlementRepository;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.WalletRepository;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.WalletTransactionRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.shared.wallet.WalletPurchaseType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
//TODO: Payment 연동 시 전체 결제·환불 트랜잭션 바깥에서 충돌 처리
public class WalletPaymentUseCase {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final HoldingRepository holdingRepository;
    private final HoldingSettlementRepository holdingSettlementRepository;
    private final LedgerRecorder ledgerRecorder;

    // 지갑 잔액을 차감하고 사용 거래 내역을 저장한다.
    // 남은 과제(동시 결제): 같은 지갑에 결제가 동시에 오면 낙관적 락 실패 또는 외래 키 데드락이 난다(잔액은 롤백으로 지켜짐).
    // 지금은 PaymentConflictExceptionHandler가 409로 돌려준다. 근본 수정은 wallet.use 대신 조건부 UPDATE
    // (walletRepository.decreaseBalance, 0행이면 잔액 부족)를 홀딩 · 거래 내역 INSERT보다 먼저 실행해 지갑 행 배타 락을 먼저 잡는 것
    public void use(Long walletId, Long amount, Long paymentId, Long orderId, WalletPurchaseType purchaseType) {
        validatePaymentRequest(walletId, amount, paymentId, orderId);

        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new ServiceException(WalletErrorCode.WALLET_NOT_FOUND));

        Optional<WalletTransaction> existing = walletTransactionRepository.findByTypeAndOrderIdAndReferenceIdAndReferenceType(
                WalletTransactionType.USE, orderId, paymentId, ReferenceType.PAYMENT
        );

        if (existing.isPresent()) {
            WalletTransaction transaction = existing.get();

            if (!transaction.getWallet().getId().equals(walletId)) {
                throw new ServiceException(WalletErrorCode.WALLET_ACCESS_DENIED);
            }

            if (!transaction.getAmount().equals(amount)) {
                throw new ServiceException(WalletErrorCode.WALLET_TRANSACTION_AMOUNT_MISMATCH);
            }

            return;
        }

        wallet.use(amount);

        Holding holding = Holding.create(
                wallet, orderId, purchaseType, amount, paymentId
        );

        holdingRepository.save(holding);

        ledgerRecorder.record(
                wallet, WalletTransactionType.USE, orderId,
                paymentId, ReferenceType.PAYMENT, null, amount);
    }

    // 결제 취소 금액을 지갑에 복구하고 환불 거래 내역을 저장한다. 같은 결제에 대해 refundId 단위로 여러 번 부분 환불할 수 있다.
    public void refund(Long walletId, Long amount, Long paymentId, Long orderId, Long refundId) {
        validatePaymentRequest(walletId, amount, paymentId, orderId);

        if (refundId == null) {
            throw new ServiceException(WalletErrorCode.WALLET_REFUND_ID_REQUIRED);
        }

        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new ServiceException(WalletErrorCode.WALLET_NOT_FOUND));

        Optional<WalletTransaction> existing = walletTransactionRepository.findByTypeAndOrderIdAndReferenceIdAndReferenceType(
                WalletTransactionType.REFUND, orderId, refundId, ReferenceType.REFUND
        );

        if (existing.isPresent()) {
            WalletTransaction transaction = existing.get();

            if (!transaction.getWallet().getId().equals(walletId)) {
                throw new ServiceException(WalletErrorCode.WALLET_ACCESS_DENIED);
            }

            if (!transaction.getAmount().equals(amount)) {
                throw new ServiceException(WalletErrorCode.WALLET_TRANSACTION_AMOUNT_MISMATCH);
            }

            return;
        }

        Holding holding = holdingRepository.findByOrderIdAndPaymentId(orderId, paymentId)
                .orElseThrow(() -> new ServiceException(WalletErrorCode.WALLET_HOLDING_NOT_FOUND));

        if (!holding.getWallet().getId().equals(walletId)) {
            throw new ServiceException(WalletErrorCode.WALLET_ACCESS_DENIED);
        }

        holding.addRefund(amount, holdingSettlementRepository.sumAmountByHoldingId(holding.getId()));

        wallet.refund(amount);

        holdingRepository.save(holding);

        ledgerRecorder.record(
                wallet, WalletTransactionType.REFUND, orderId,
                refundId, ReferenceType.REFUND, null, amount);
    }

    private void validatePaymentRequest(
            Long walletId, Long amount, Long paymentId, Long orderId
    ) {
        if (walletId == null) {
            throw new ServiceException(WalletErrorCode.WALLET_ID_REQUIRED);
        }

        if (amount == null || amount <= 0) {
            throw new ServiceException(WalletErrorCode.WALLET_INVALID_AMOUNT);
        }

        if (paymentId == null) {
            throw new ServiceException(WalletErrorCode.WALLET_PAYMENT_ID_REQUIRED);
        }

        if (orderId == null) {
            throw new ServiceException(WalletErrorCode.WALLET_ORDER_ID_REQUIRED);
        }
    }
}
