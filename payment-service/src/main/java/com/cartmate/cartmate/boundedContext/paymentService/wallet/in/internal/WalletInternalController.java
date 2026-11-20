package com.cartmate.cartmate.boundedContext.paymentService.wallet.in.internal;

import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.WalletFacade;
import com.cartmate.cartmate.global.rsData.RsData;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 지갑 내부 API (회원 서버만 부른다). 프론트 API 문서에 나오지 않게 숨긴다
@Hidden
@RestController
@RequestMapping("/internal/wallets")
@RequiredArgsConstructor
public class WalletInternalController {

    private final WalletFacade walletFacade;

    // 회원 탈퇴 확인: 진행 중(대기·처리 중) 예치금 출금 금액. 실패·취소되면 잔액으로 돌아오므로 끝날 때까지 탈퇴를 막는다
    @GetMapping("/withdrawing-amount")
    public RsData<Long> withdrawingAmount(@RequestParam Long memberId) {
        return new RsData<>("200-1", "진행 중 출금 금액", walletFacade.getWithdrawingAmount(memberId));
    }
}
