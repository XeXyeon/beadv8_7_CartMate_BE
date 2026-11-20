package com.cartmate.cartmate.boundedContext.paymentService.wallet.in.admin;

import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.WalletFacade;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.WalletWithdrawService;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.dto.AdminWithdrawAccountResponse;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.dto.AdminWithdrawCompleteRequest;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.dto.AdminWithdrawFailRequest;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.dto.AdminWithdrawOrderResponse;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WithdrawStatus;
import com.cartmate.cartmate.global.rsData.RsData;
import com.cartmate.cartmate.global.security.SecurityUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * 관리자 예치금 출금 처리 (/api/admin/** → OTP를 거친 관리자만).
 * manual 모드 순서: 목록(PENDING) → 계좌 보기 → 송금 시작 → 은행에서 직접 송금 → 완료(메모) 또는 실패(사유, 잔액 복구).
 */
@RestController
@RequestMapping("/api/admin/wallets/withdraws")
@RequiredArgsConstructor
public class AdminWalletWithdrawController {

    private final WalletFacade walletFacade;
    private final WalletWithdrawService walletWithdrawService;

    // status가 없으면 전체 (오래된 건부터 처리하도록 기본은 오름차순)
    @GetMapping
    public RsData<Page<AdminWithdrawOrderResponse>> getAdminWithdraws(
            @RequestParam(required = false) WithdrawStatus status,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        return new RsData<>("200-1", "출금 목록입니다.", walletFacade.getWithdrawsForAdmin(status, pageable));
    }

    // 계좌번호 원문 (열람 기록을 남긴다)
    @GetMapping("/{withdrawOrderId}/account")
    public RsData<AdminWithdrawAccountResponse> getAdminWithdrawAccount(@AuthenticationPrincipal SecurityUser admin,
                                                        @PathVariable Long withdrawOrderId) {
        return new RsData<>("200-1", "출금 계좌입니다.", walletWithdrawService.revealAccount(admin.memberId(), withdrawOrderId));
    }

    @PostMapping("/{withdrawOrderId}/start")
    public RsData<Void> startAdminWithdraw(@AuthenticationPrincipal SecurityUser admin, @PathVariable Long withdrawOrderId) {
        walletWithdrawService.startByAdmin(admin.memberId(), withdrawOrderId, LocalDateTime.now());
        return new RsData<>("200-1", "송금을 시작했습니다. 송금한 뒤 완료를 눌러주세요.");
    }

    @PostMapping("/{withdrawOrderId}/complete")
    public RsData<Void> completeAdminWithdraw(@AuthenticationPrincipal SecurityUser admin, @PathVariable Long withdrawOrderId,
                                 @Valid @RequestBody AdminWithdrawCompleteRequest request) {
        walletWithdrawService.completeByAdmin(admin.memberId(), withdrawOrderId, request.memo(), LocalDateTime.now());
        return new RsData<>("200-1", "출금을 완료했습니다.");
    }

    @PostMapping("/{withdrawOrderId}/fail")
    public RsData<Void> failAdminWithdraw(@AuthenticationPrincipal SecurityUser admin, @PathVariable Long withdrawOrderId,
                             @Valid @RequestBody AdminWithdrawFailRequest request) {
        walletWithdrawService.failByAdmin(admin.memberId(), withdrawOrderId, request.reason().trim(), LocalDateTime.now());
        return new RsData<>("200-1", "출금을 실패 처리하고 잔액을 돌려주었습니다.");
    }
}
