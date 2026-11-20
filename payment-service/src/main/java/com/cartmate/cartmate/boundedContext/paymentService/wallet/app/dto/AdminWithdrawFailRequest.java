package com.cartmate.cartmate.boundedContext.paymentService.wallet.app.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 관리자 실패 처리: 사유는 회원에게도 보인다. 잔액은 돌려준다
public record AdminWithdrawFailRequest(
        @NotBlank(message = "실패 사유를 입력해주세요.")
        @Size(max = 200, message = "사유는 200자 이하로 입력해주세요.")
        String reason
) {
}
