package com.cartmate.cartmate.boundedContext.memberService.member.dto;

// 결제 서버 내부 API 전용: 출금 신청 때 계좌번호 원문을 넘긴다 (결제 서버가 자기 키로 다시 잠가 보관)
public record PayoutAccountInternalResponse(String bankCode, String bankName, String accountHolder, String accountNumber) {
}
