package com.cartmate.cartmate.boundedContext.paymentService.wallet.app;

import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.dto.AdminWithdrawOrderResponse;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.dto.WalletWithdrawOrderResponse;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WithdrawStatus;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.WalletWithdrawOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

// 예치금 출금 조회 (회원 내역, 관리자 목록, 탈퇴 확인, 스케줄러 대상)
@Service
@RequiredArgsConstructor
public class WalletWithdrawQueryUseCase {

    private final WalletWithdrawOrderRepository walletWithdrawOrderRepository;

    public Page<WalletWithdrawOrderResponse> getMyWithdraws(Long walletId, Pageable pageable) {
        return walletWithdrawOrderRepository.findByWalletId(walletId, pageable).map(WalletWithdrawOrderResponse::from);
    }

    // status가 없으면 전체
    public Page<AdminWithdrawOrderResponse> getForAdmin(WithdrawStatus status, Pageable pageable) {
        return (status == null
                ? walletWithdrawOrderRepository.findAllWithWallet(pageable)
                : walletWithdrawOrderRepository.findByStatus(status, pageable))
                .map(AdminWithdrawOrderResponse::from);
    }

    public long getWithdrawingAmount(Long memberId) {
        return walletWithdrawOrderRepository.sumAmountByMemberIdAndStatusIn(memberId, WalletWithdrawUseCase.IN_PROGRESS);
    }

    public List<Long> findStalePendingIds(LocalDateTime before, int limit) {
        return walletWithdrawOrderRepository.findIdsByStatusCreatedBefore(WithdrawStatus.PENDING, before, Limit.of(limit));
    }

    public List<Long> findUnconfirmedIds(LocalDateTime before, int limit) {
        return walletWithdrawOrderRepository.findIdsByStatusProcessingBefore(WithdrawStatus.PROCESSING, before,
                Limit.of(limit));
    }
}
