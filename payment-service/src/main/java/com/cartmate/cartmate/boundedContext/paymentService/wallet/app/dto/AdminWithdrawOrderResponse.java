package com.cartmate.cartmate.boundedContext.paymentService.wallet.app.dto;

import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WalletWithdrawOrder;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WithdrawStatus;

import java.time.LocalDateTime;

// 관리자 출금 처리 목록. 계좌번호 원문은 따로 (AdminWithdrawAccountResponse, 열람 기록을 남김)
public record AdminWithdrawOrderResponse(
        Long withdrawOrderId,
        Long memberId,
        Long amount,
        WithdrawStatus status,
        String statusLabel,
        String transferId,
        String bankName,
        String accountHolder,
        String accountNumberMasked,
        String resultMessage,
        Long processedBy,
        boolean reviewRequested,
        LocalDateTime requestedAt,
        LocalDateTime processingStartedAt,
        LocalDateTime finishedAt
) {
    public static AdminWithdrawOrderResponse from(WalletWithdrawOrder order) {
        return new AdminWithdrawOrderResponse(
                order.getId(),
                order.getWallet().getMemberId(),
                order.getAmount(),
                order.getStatus(),
                order.getStatus().getLabel(),
                order.getTransferId(),
                order.getBankName(),
                order.getAccountHolder(),
                order.getAccountNumberMasked(),
                order.getResultMessage(),
                order.getProcessedBy(),
                order.getReviewRequestedAt() != null,
                order.getCreatedAt(),
                order.getProcessingStartedAt(),
                order.getFinishedAt()
        );
    }
}
