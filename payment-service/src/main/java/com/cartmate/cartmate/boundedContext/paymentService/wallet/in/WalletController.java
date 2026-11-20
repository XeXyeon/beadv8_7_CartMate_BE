package com.cartmate.cartmate.boundedContext.paymentService.wallet.in;

import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.WalletChargeConfirmService;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.WalletFacade;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.WalletRetryService;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.WalletWithdrawService;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WalletChargeOrder;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.dto.*;
import com.cartmate.cartmate.global.rsData.RsData;
import com.cartmate.cartmate.global.security.SecurityUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;


@RestController
@RequestMapping("/api/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletFacade walletFacade;
    private final WalletRetryService walletRetryService;
    private final WalletChargeConfirmService walletChargeConfirmService;
    private final WalletWithdrawService walletWithdrawService;

    @GetMapping("/me")
    public RsData<WalletBalanceResponse> getMyWallet(
            @AuthenticationPrincipal SecurityUser user
            ) {
        Long memberId = user.memberId();

        return new RsData<>("200-1", "지갑 조회 성공했습니다.", walletFacade.getWallet(memberId));
    }

    @GetMapping("/me/transactions")
    public RsData<Page<WalletTransactionResponse>> getMyWalletTransactions(
            @AuthenticationPrincipal SecurityUser user,
            @PageableDefault(
                    size = 20,
                    sort = {"createdAt", "id"},
                    direction = Sort.Direction.DESC
            ) Pageable pageable
    ) {
        Long memberId = user.memberId();

        WalletBalanceResponse wallet = walletFacade.getWallet(memberId);

        Long walletId = wallet.walletId();

        return new RsData<>("200-1", "거래 내역 조회 성공했습니다.", walletFacade.getTransactions(walletId, pageable));
    }

    @GetMapping("/me/transactions/summary")
    public RsData<WalletTransactionSummaryResponse> getMyWalletTransactionSummary(
            @AuthenticationPrincipal SecurityUser user
    ) {
        Long walletId = walletFacade.getWallet(user.memberId()).walletId();

        return new RsData<>("200-1", "거래 내역 요약 조회 성공했습니다.", walletFacade.getTransactionSummary(walletId));
    }

    @PostMapping("/me/charges")
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<WalletChargeOrderResponse> walletChargeOrder(
            @AuthenticationPrincipal SecurityUser user,
            @Valid @RequestBody WalletChargeOrderRequest chargeOrderRequest
    ) {
        Long memberId = user.memberId();

        WalletBalanceResponse wallet = walletFacade.getWallet(memberId);

        WalletChargeOrder chargeOrder = walletRetryService.chargeOrder(
                wallet.walletId(),
                chargeOrderRequest.amount(),
                chargeOrderRequest.idempotencyKey()
        );

        return new RsData<>("201-1", "충전 주문이 생성되었습니다.", WalletChargeOrderResponse.from(chargeOrder));
    }

    @PostMapping("/me/charges/confirm")
    public RsData<WalletBalanceResponse> walletChargeConfirm(
            @AuthenticationPrincipal SecurityUser user,
            @Valid @RequestBody WalletChargeConfirmRequest chargeConfirmRequest
            ) {
        Long memberId = user.memberId();

        WalletBalanceResponse wallet = walletFacade.getWallet(memberId);

        // 토스 결제 승인을 요청하고, 승인된 결제가 충전 주문과 같을 때만 지갑에 충전한다.
        walletChargeConfirmService.confirm(
                wallet.walletId(),
                chargeConfirmRequest.orderNumber(),
                chargeConfirmRequest.paymentKey(),
                chargeConfirmRequest.amount()
        );

        return new RsData<>("200-1", "지갑 충전이 완료되었습니다.", walletFacade.getWallet(memberId));
    }

    // 예치금 출금 신청: 등록된 출금 계좌(회원 서버)로 보낸다. 잔액은 신청할 때 바로 빠지고, 실패·취소되면 돌아온다.
    // 같은 멱등 키로 다시 보내면 처음 건을 그대로 돌려준다 (잔액을 다시 빼지 않음)
    @PostMapping("/me/withdraws")
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<WalletWithdrawOrderResponse> walletWithdrawOrder(
            @AuthenticationPrincipal SecurityUser user,
            @Valid @RequestBody WalletWithdrawOrderRequest withdrawOrderRequest
    ) {
        WalletWithdrawOrderResponse withdrawOrder = walletWithdrawService.request(
                user.memberId(),
                withdrawOrderRequest.amount(),
                withdrawOrderRequest.idempotencyKey(),
                LocalDateTime.now()
        );

        return new RsData<>("201-1", "출금을 신청했습니다.", withdrawOrder);
    }

    @GetMapping("/me/withdraws")
    public RsData<Page<WalletWithdrawOrderResponse>> getMyWithdraws(
            @AuthenticationPrincipal SecurityUser user,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Long walletId = walletFacade.getWallet(user.memberId()).walletId();

        return new RsData<>("200-1", "출금 내역 조회 성공했습니다.", walletFacade.getMyWithdraws(walletId, pageable));
    }

    // 대기 중일 때만 취소할 수 있다 (송금을 시작했으면 돈이 나갔을 수 있다). 잔액은 돌아온다
    @PostMapping("/me/withdraws/{withdrawOrderId}/cancel")
    public RsData<WalletBalanceResponse> cancelWithdraw(
            @AuthenticationPrincipal SecurityUser user,
            @PathVariable Long withdrawOrderId
    ) {
        walletWithdrawService.cancel(user.memberId(), withdrawOrderId, LocalDateTime.now());

        return new RsData<>("200-1", "출금을 취소했습니다.", walletFacade.getWallet(user.memberId()));
    }
}
