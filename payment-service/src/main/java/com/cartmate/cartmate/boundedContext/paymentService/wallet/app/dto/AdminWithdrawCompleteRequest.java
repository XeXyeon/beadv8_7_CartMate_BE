package com.cartmate.cartmate.boundedContext.paymentService.wallet.app.dto;

import jakarta.validation.constraints.Size;

// 관리자 송금 완료: 메모(이체 확인 번호 등)
public record AdminWithdrawCompleteRequest(
        @Size(max = 200, message = "메모는 200자 이하로 입력해주세요.")
        String memo
) {
}
