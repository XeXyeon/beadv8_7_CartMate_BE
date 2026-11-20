package com.cartmate.cartmate.boundedContext.paymentService.wallet.app.dto;

import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WalletWithdrawOrder;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WithdrawStatus;

import java.time.LocalDateTime;

// 회원 화면용 출금 내역. 계좌번호는 가린 값만
public record WalletWithdrawOrderResponse(
        Long withdrawOrderId,
        Long amount,
        WithdrawStatus status,
        String statusLabel,
        String bankName,
        String accountNumberMasked,
        String resultMessage,
        LocalDateTime requestedAt,
        LocalDateTime finishedAt
) {
    public static WalletWithdrawOrderResponse from(WalletWithdrawOrder withdrawOrder) {
        return new WalletWithdrawOrderResponse(
                withdrawOrder.getId(),
                withdrawOrder.getAmount(),
                withdrawOrder.getStatus(),
                withdrawOrder.getStatus().getLabel(),
                withdrawOrder.getBankName(),
                withdrawOrder.getAccountNumberMasked(),
                // 실패 사유만 회원에게 보인다 (완료 메모는 관리자용)
                withdrawOrder.getStatus() == WithdrawStatus.FAILED ? withdrawOrder.getResultMessage() : null,
                withdrawOrder.getCreatedAt(),
                withdrawOrder.getFinishedAt()
        );
    }
}
