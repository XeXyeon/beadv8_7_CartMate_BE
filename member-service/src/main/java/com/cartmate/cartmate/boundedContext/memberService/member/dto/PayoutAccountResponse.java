package com.cartmate.cartmate.boundedContext.memberService.member.dto;

import com.cartmate.cartmate.boundedContext.memberService.member.domain.MemberPayoutAccount;

import java.time.LocalDateTime;

// 화면용: 계좌번호는 끝 4자리만
public record PayoutAccountResponse(String bankName, String accountHolder, String maskedAccountNumber,
                                    LocalDateTime updatedAt) {

    public static PayoutAccountResponse from(MemberPayoutAccount account) {
        return new PayoutAccountResponse(account.getBankName(), account.getAccountHolder(),
                account.getMaskedAccountNumber(), account.getModifyDate());
    }
}
