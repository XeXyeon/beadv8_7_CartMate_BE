package com.cartmate.cartmate.boundedContext.paymentService.wallet.app;

import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.dto.WalletBalanceResponse;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.dto.WalletChargeOrderInfo;
import com.cartmate.cartmate.shared.wallet.WalletPurchaseType;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WalletChargeOrder;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.dto.WalletTransactionResponse;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.dto.WalletTransactionSummaryResponse;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.dto.AdminWithdrawOrderResponse;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.dto.WalletWithdrawOrderResponse;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WalletWithdrawOrder;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WalletWithdrawOrder.WithdrawAccount;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WithdrawStatus;
import com.cartmate.cartmate.shared.wallet.dto.SettleAndDepositRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;


@Service
@RequiredArgsConstructor
public class WalletFacade {
    private final WalletCreateUseCase walletCreateUseCase;
    private final WalletEnsureUseCase walletEnsureUseCase;
    private final WalletQueryUseCase walletQueryUseCase;
    private final WalletChargeUseCase walletChargeUseCase;
    private final WalletPaymentUseCase walletPaymentUseCase;
    private final WalletSettlementUseCase walletSettlementUseCase;
    private final WalletWithdrawUseCase walletWithdrawUseCase;
    private final WalletWithdrawQueryUseCase walletWithdrawQueryUseCase;

    // 회원 지갑 생성을 요청한다.
    @Transactional
    public void createWallet(Long memberId) {
        walletCreateUseCase.createWallet(memberId);
    }

    // 회원의 지갑을 조회한다. 없으면(가입 이벤트가 끝내 처리되지 못한 회원 등) 그 자리에서 만들어 0원 지갑을 준다.
    // 트랜잭션을 걸지 않는다: 만들기는 별도 트랜잭션으로 커밋하고 새 트랜잭션에서 다시 읽어야 보인다
    // (바깥 트랜잭션이 먼저 읽었으면 MySQL 반복 읽기 스냅숏 때문에 방금 만든 지갑이 안 보임). 조회는 WalletQueryUseCase가 연다
    public WalletBalanceResponse getWallet(Long memberId) {
        return walletQueryUseCase.findWallet(memberId).orElseGet(() -> {
            walletEnsureUseCase.ensure(memberId);
            return walletQueryUseCase.getWallet(memberId);
        });
    }

    // 지갑 거래 내역을 페이지 조회한다.
    @Transactional(readOnly = true)
    public Page<WalletTransactionResponse> getTransactions(
            Long walletId,
            Pageable pageable
    ) {
        return walletQueryUseCase.getTransactions(walletId, pageable);
    }

    // 지갑 거래 내역의 충전·결제 합계를 조회한다.
    @Transactional(readOnly = true)
    public WalletTransactionSummaryResponse getTransactionSummary(Long walletId) {
        return walletQueryUseCase.getTransactionSummary(walletId);
    }

    // 주문 금액 충전·거래 내역 저장·주문 완료를 하나의 트랜잭션으로 처리한다. 완료 주문은 재충전하지 않는다.
    @Transactional
    public void charge(Long walletId, String orderNumber, String paymentKey, Long amount) {
        walletChargeUseCase.charge(walletId, orderNumber, paymentKey, amount);
    }

    // 결제 ID 기준으로 중복 사용을 확인하고 잔액차감·홀딩생성·거래내역저장을 하나의 트랜잭션으로 처리한다.
    @Transactional
    public void use(Long walletId, Long amount, Long paymentId, Long orderId, WalletPurchaseType purchaseType) {
        walletPaymentUseCase.use(walletId, amount, paymentId, orderId, purchaseType);
    }

    // 환불 ID 기준으로 중복 환불을 확인하고 홀딩 환불누적·잔액복구·환불거래저장을 하나의 트랜잭션으로 처리한다.
    @Transactional
    public void refund(Long walletId, Long amount, Long paymentId, Long orderId, Long refundId) {
        walletPaymentUseCase.refund(walletId, amount, paymentId, orderId, refundId);
    }

    // 충전 승인 전에 검증할 수 있도록 충전 주문의 값을 조회한다.
    @Transactional(readOnly = true)
    public WalletChargeOrderInfo getChargeOrder(String orderNumber) {
        return WalletChargeOrderInfo.from(walletChargeUseCase.getChargeOrder(orderNumber));
    }

    // 충전 주문을 생성하거나 같은 멱등성 키·지갑·금액의 기존 주문을 반환한다.
    @Transactional
    public WalletChargeOrder chargeOrder(Long walletId, Long amount, String idempotencyKey) {
        return walletChargeUseCase.createChargeOrder(walletId, amount, idempotencyKey);
    }

    // 대기 중인 충전 주문을 취소한다. 이미 취소된 주문은 재처리하지 않으며 잔액은 변경하지 않는다.
    @Transactional
    public void cancel(Long walletId, String orderNumber) {
        walletChargeUseCase.cancel(walletId, orderNumber);
    }

    // 대기 중인 충전 주문을 실패 처리한다. 이미 실패한 주문은 재처리하지 않으며 잔액은 변경하지 않는다.
    @Transactional
    public void fail(Long walletId, String orderNumber) {
        walletChargeUseCase.fail(walletId, orderNumber);
    }

    // 정산 ID 기준으로 중복 정산을 확인하고 홀딩에서 꺼내기·판매자 정산금 입금·플랫폼 수수료 입금을 하나의 트랜잭션으로 처리한다.
    @Transactional
    public void settleAndDeposit(SettleAndDepositRequest request) {
        walletSettlementUseCase.settleAndDeposit(request);
    }

    // ===== 예치금 출금 (이체 같은 외부 호출은 여기 밖에서: WalletWithdrawService · WalletWithdrawTransferProcessor) =====

    // 잔액 조건부 차감·출금 행·원장을 한 트랜잭션으로. 같은 멱등 키면 기존 건
    @Transactional
    public WalletWithdrawOrderResponse requestWithdraw(Long walletId, Long amount, String idempotencyKey,
                                                       String transferId, WithdrawAccount account, LocalDateTime now) {
        return WalletWithdrawOrderResponse.from(
                walletWithdrawUseCase.request(walletId, amount, idempotencyKey, transferId, account, now));
    }

    @Transactional
    public boolean startWithdrawProcessing(Long withdrawOrderId, Long processedBy, LocalDateTime now) {
        return walletWithdrawUseCase.startProcessing(withdrawOrderId, processedBy, now);
    }

    @Transactional
    public boolean completeWithdraw(Long withdrawOrderId, String message, Long processedBy, LocalDateTime now) {
        return walletWithdrawUseCase.complete(withdrawOrderId, message, processedBy, now);
    }

    // 실패 확정 + 잔액 복구를 한 트랜잭션으로
    @Transactional
    public boolean failWithdraw(Long withdrawOrderId, String reason, Long processedBy, LocalDateTime now) {
        return walletWithdrawUseCase.fail(withdrawOrderId, reason, processedBy, now);
    }

    // 회원 취소 (대기 중일 때만) + 잔액 복구
    @Transactional
    public void cancelWithdraw(Long walletId, Long withdrawOrderId, LocalDateTime now) {
        walletWithdrawUseCase.cancel(walletId, withdrawOrderId, now);
    }

    @Transactional
    public boolean requestWithdrawReview(Long withdrawOrderId, LocalDateTime now) {
        return walletWithdrawUseCase.requestReview(withdrawOrderId, now);
    }

    @Transactional(readOnly = true)
    public WalletWithdrawOrder getWithdrawOrder(Long withdrawOrderId) {
        return walletWithdrawUseCase.findOrder(withdrawOrderId);
    }

    @Transactional(readOnly = true)
    public Page<WalletWithdrawOrderResponse> getMyWithdraws(Long walletId, Pageable pageable) {
        return walletWithdrawQueryUseCase.getMyWithdraws(walletId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AdminWithdrawOrderResponse> getWithdrawsForAdmin(WithdrawStatus status, Pageable pageable) {
        return walletWithdrawQueryUseCase.getForAdmin(status, pageable);
    }

    @Transactional(readOnly = true)
    public long getWithdrawingAmount(Long memberId) {
        return walletWithdrawQueryUseCase.getWithdrawingAmount(memberId);
    }

    @Transactional(readOnly = true)
    public List<Long> findStalePendingWithdrawIds(LocalDateTime before, int limit) {
        return walletWithdrawQueryUseCase.findStalePendingIds(before, limit);
    }

    @Transactional(readOnly = true)
    public List<Long> findUnconfirmedWithdrawIds(LocalDateTime before, int limit) {
        return walletWithdrawQueryUseCase.findUnconfirmedIds(before, limit);
    }
}
