package com.cartmate.cartmate.boundedContext.memberService.member.in;

import com.cartmate.cartmate.boundedContext.memberService.member.app.MemberPayoutAccountFacade;
import com.cartmate.cartmate.boundedContext.memberService.member.dto.PayoutAccountCodeRequest;
import com.cartmate.cartmate.boundedContext.memberService.member.dto.PayoutAccountRequest;
import com.cartmate.cartmate.boundedContext.memberService.member.dto.PayoutAccountResponse;
import com.cartmate.cartmate.global.rsData.RsData;
import com.cartmate.cartmate.global.security.SecurityUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

// 예치금 출금 계좌. 탈퇴 대기 중에도 쓸 수 있다 (남은 예치금을 돌려받아야 탈퇴가 확정되므로 — common WithdrawalPendingFilter)
@RestController
@RequestMapping("/api/members/me/payout-account")
@RequiredArgsConstructor
public class MemberPayoutAccountController {

    private final MemberPayoutAccountFacade payoutAccountFacade;

    // 등록 전이면 data가 null
    @GetMapping
    public RsData<PayoutAccountResponse> myPayoutAccount(@AuthenticationPrincipal SecurityUser user) {
        return payoutAccountFacade.get(user.memberId())
                .map(account -> new RsData<>("200-1", "출금 계좌 조회 성공", account))
                .orElseGet(() -> new RsData<>("200-2", "등록된 출금 계좌가 없습니다.", null));
    }

    // 등록·변경 전 이메일 인증번호 발송 (회원 가입 이메일로). data: 유효 시간(초)
    @PostMapping("/email-code")
    public RsData<Integer> sendPayoutAccountCode(@AuthenticationPrincipal SecurityUser user) {
        return new RsData<>("200-1", "인증번호를 보냈습니다.", payoutAccountFacade.sendCode(user.memberId(), Instant.now()));
    }

    @PostMapping("/email-code/verify")
    public RsData<Void> verifyPayoutAccountCode(@AuthenticationPrincipal SecurityUser user,
                                   @Valid @RequestBody PayoutAccountCodeRequest request) {
        payoutAccountFacade.verifyCode(user.memberId(), request.code(), Instant.now());
        return new RsData<>("200-1", "인증되었습니다. 출금 계좌를 등록해주세요.");
    }

    // 본인 명의 계좌 등록·변경 (실명 확인)
    @PutMapping
    public RsData<PayoutAccountResponse> registerPayoutAccount(@AuthenticationPrincipal SecurityUser user,
                                                  @Valid @RequestBody PayoutAccountRequest request) {
        return new RsData<>("200-1", "출금 계좌가 등록되었습니다.",
                payoutAccountFacade.register(user.memberId(), request, Instant.now()));
    }

    // 판매자: 정산 계좌를 출금 계좌로
    @PutMapping("/from-seller")
    public RsData<PayoutAccountResponse> registerPayoutAccountFromSeller(@AuthenticationPrincipal SecurityUser user) {
        return new RsData<>("200-1", "정산 계좌를 출금 계좌로 등록했습니다.",
                payoutAccountFacade.registerFromSeller(user.memberId(), Instant.now()));
    }
}
