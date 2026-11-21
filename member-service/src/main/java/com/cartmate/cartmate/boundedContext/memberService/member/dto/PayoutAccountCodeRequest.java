package com.cartmate.cartmate.boundedContext.memberService.member.dto;

import com.cartmate.cartmate.boundedContext.memberService.member.domain.MemberPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

// 출금 계좌 등록·변경 전 이메일 인증번호 확인
public record PayoutAccountCodeRequest(
        @NotBlank(message = "인증번호는 필수입니다.")
        @Pattern(regexp = MemberPolicy.PHONE_CODE_REGEX, message = "인증번호는 숫자 6자리입니다.")
        String code
) {
}
