package com.cartmate.cartmate.boundedContext.paymentService.wallet.app.dto;

// 관리자가 직접 송금할 때만 보는 계좌번호 원문
public record AdminWithdrawAccountResponse(Long withdrawOrderId, String bankName, String accountHolder,
                                           String accountNumber, Long amount) {
}
