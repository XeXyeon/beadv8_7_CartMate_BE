package com.cartmate.cartmate.boundedContext.memberService.member.dto;

import com.cartmate.cartmate.boundedContext.memberService.member.domain.MemberPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 출금 계좌 등록·변경. 이메일 인증을 먼저 마쳐야 한다
public record PayoutAccountRequest(
        @NotBlank(message = "은행을 선택해주세요.")
        String bankName,

        @NotBlank(message = "예금주를 입력해주세요.")
        @Size(max = 50)
        String accountHolder,

        @NotBlank(message = "계좌번호를 입력해주세요.")
        @Pattern(regexp = MemberPolicy.ACCOUNT_NUMBER_REGEX, message = MemberPolicy.ACCOUNT_NUMBER_MESSAGE)
        String accountNumber
) {
}
