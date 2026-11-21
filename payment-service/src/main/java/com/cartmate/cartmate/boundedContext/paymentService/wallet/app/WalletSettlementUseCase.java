package com.cartmate.cartmate.boundedContext.paymentService.wallet.app;

import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.Holding;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.HoldingSettlement;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.HoldingSettlementAllocator;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.HoldingStatus;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.ReferenceType;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.Wallet;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WalletTransactionType;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.exception.WalletErrorCode;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.HoldingRepository;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.HoldingSettlementRepository;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.WalletRepository;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.WalletTransactionRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.shared.wallet.dto.SettleAndDepositRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WalletSettlementUseCase {

    private final WalletRepository walletRepository;
    private final HoldingRepository holdingRepository;
    private final HoldingSettlementRepository holdingSettlementRepository;
    private final PlatformWalletProvider platformWalletProvider;
    private final LedgerRecorder ledgerRecorder;
    private final WalletTransactionRepository walletTransactionRepository;

    // 정산 한 건을 처리한다. 판매 금액(판매자 부담 쿠폰 할인을 뺀 금액)이 판매자 정산금과 플랫폼 수수료로 나뉘어 입금된다.
    // 판매 금액 중 플랫폼 부담 쿠폰 할인은 구매자가 내지 않아 홀딩에 없으므로 플랫폼 지갑이 낸다(COUPON_SUBSIDY).
    // 1) 같은 정산 ID로 이미 처리됐으면 아무것도 바꾸지 않고 종료한다(금액이 다르면 예외).
    // 2) 주문의 홀딩을 조회하고(커밋할 때 홀딩 버전을 강제로 올린다), 남은 금액 비율로 "판매 금액 - 플랫폼 부담 할인"을 홀딩별로 나눠 꺼내 HoldingSettlement로 기록한다.
    //    꺼낼 금액이 0원이면(플랫폼 쿠폰이 상품 금액 전부를 할인) 홀딩은 건드리지 않는다.
    // 3) 판매자 지갑에 정산금(SETTLEMENT)을 입금하고, 플랫폼 지갑은 쿠폰 할인 지원(COUPON_SUBSIDY)을 내고 수수료(FEE)를 받는다.
    // 어느 단계에서든 실패하면 예외가 발생하며, 같은 트랜잭션이라 모두 롤백된다.
    public void settleAndDeposit(SettleAndDepositRequest request) {
        validate(request);

        long platformDiscount = request.platformDiscountOrZero();
        long holdingAmount = request.salesAmount() - platformDiscount;

        // 1) 이미 처리된 정산인지 확인한다.
        if (alreadySettled(request, holdingAmount)) {
            return;
        }

        // 2) 판매자 지갑과 주문의 홀딩(정산이 끝나거나 취소된 것은 제외)을 조회한다. 동시 정산은 호출하는 쪽의 주문 키 분산락이 막는다.
        // 판매자 지갑이 없으면(가입 이벤트가 처리되지 않은 판매자) 이 트랜잭션에서 만들어 바로 입금한다 → 정산금이 묶이지 않게
        Wallet sellerWallet = walletRepository.findByMemberId(request.sellerMemberId())
                .orElseGet(() -> walletRepository.save(Wallet.createWallet(request.sellerMemberId())));

        if (holdingAmount > 0) {
            withdrawFromHoldings(request, holdingAmount);
        }

        // 3) 판매자에게는 정산금을 입금한다.
        sellerWallet.deposit(request.payoutAmount());

        ledgerRecorder.record(
                sellerWallet, WalletTransactionType.SETTLEMENT, request.orderId(),
                request.payoutId(), ReferenceType.SETTLEMENT, null, request.payoutAmount());

        // 플랫폼 지갑: 쿠폰 할인 지원을 내고, 수수료(판매 금액 - 정산금)를 받는다. 장부 대사를 위해 두 거래로 나눠 기록한다.
        long fee = request.salesAmount() - request.payoutAmount();

        if (platformDiscount > 0 || fee > 0) {
            Wallet platformWallet = platformWalletProvider.getPlatformWallet();

            if (platformDiscount > 0) {
                platformWallet.subsidize(platformDiscount);

                ledgerRecorder.record(
                        platformWallet, WalletTransactionType.COUPON_SUBSIDY, request.orderId(),
                        request.payoutId(), ReferenceType.SETTLEMENT, null, platformDiscount);
            }

            if (fee > 0) {
                platformWallet.deposit(fee);

                ledgerRecorder.record(
                        platformWallet, WalletTransactionType.FEE, request.orderId(),
                        request.payoutId(), ReferenceType.SETTLEMENT, null, fee);
            }
        }
    }

    // 같은 정산 ID의 처리 흔적이 있는지 본다. 홀딩에서 꺼낸 기록이 있으면 금액을 비교하고(다르면 예외),
    // 꺼낼 금액이 0원인 정산은 홀딩 기록이 없으므로 판매자 입금 거래(SETTLEMENT)로 확인한다.
    private boolean alreadySettled(SettleAndDepositRequest request, long holdingAmount) {
        List<HoldingSettlement> existing = holdingSettlementRepository.findAllByPayoutId(request.payoutId());

        if (!existing.isEmpty()) {
            long settled = existing.stream().mapToLong(HoldingSettlement::getAmount).sum();

            if (settled != holdingAmount) {
                throw new ServiceException(WalletErrorCode.WALLET_TRANSACTION_AMOUNT_MISMATCH);
            }

            return true;
        }

        return holdingAmount == 0 && walletTransactionRepository.findByTypeAndOrderIdAndReferenceIdAndReferenceType(
                WalletTransactionType.SETTLEMENT, request.orderId(), request.payoutId(), ReferenceType.SETTLEMENT
        ).isPresent();
    }

    // 주문의 남은 홀딩에서 amount를 남은 금액 비율로 나눠 꺼내고 HoldingSettlement로 기록한다.
    private void withdrawFromHoldings(SettleAndDepositRequest request, long amount) {
        List<Holding> holdings = holdingRepository
                .findAllByOrderIdAndPurchaseType(request.orderId(), request.purchaseType()).stream()
                .filter(holding -> holding.getStatus() == HoldingStatus.RESERVED)
                .toList();

        if (holdings.isEmpty()) {
            throw new ServiceException(WalletErrorCode.WALLET_HOLDING_NOT_FOUND);
        }

        // 홀딩별로 이미 정산에 쓰인 금액과 남은 금액을 구한 뒤, 판매 금액을 남은 금액 비율로 나눈다.
        List<Long> settledAmounts = holdings.stream()
                .map(holding -> holdingSettlementRepository.sumAmountByHoldingId(holding.getId()))
                .toList();

        List<Long> remainings = new ArrayList<>();
        for (int i = 0; i < holdings.size(); i++) {
            remainings.add(holdings.get(i).remainingAmount(settledAmounts.get(i)));
        }

        List<Long> shares = HoldingSettlementAllocator.allocate(remainings, amount);

        // 홀딩마다 꺼낸 금액을 기록하고, 남은 금액이 없어진 홀딩은 정산 완료로 바꾼다.
        for (int i = 0; i < holdings.size(); i++) {
            if (shares.get(i) == 0) {
                continue;
            }

            Holding holding = holdings.get(i);

            holdingSettlementRepository.save(HoldingSettlement.create(holding, request.payoutId(), shares.get(i)));
            holding.closeIfExhausted(settledAmounts.get(i) + shares.get(i));
        }
    }

    // 요청 값을 검증한다. 판매자 회원 ID, 정산 ID, 주문 ID는 필수이고, 판매 금액과 정산금은 0보다 커야 하며 정산금이 판매 금액을 넘을 수 없다.
    private void validate(SettleAndDepositRequest request) {
        if (request.sellerMemberId() == null) {
            throw new ServiceException(WalletErrorCode.WALLET_MEMBER_ID_REQUIRED);
        }

        if (request.payoutId() == null) {
            throw new ServiceException(WalletErrorCode.WALLET_PAYOUT_ID_REQUIRED);
        }

        if (request.orderId() == null) {
            throw new ServiceException(WalletErrorCode.WALLET_ORDER_ID_REQUIRED);
        }

        if (request.purchaseType() == null) {
            throw new ServiceException(WalletErrorCode.WALLET_PURCHASE_TYPE_REQUIRED);
        }

        if (request.salesAmount() == null || request.salesAmount() <= 0
                || request.payoutAmount() == null || request.payoutAmount() <= 0
                || request.payoutAmount() > request.salesAmount()) {
            throw new ServiceException(WalletErrorCode.WALLET_INVALID_AMOUNT);
        }

        // 플랫폼 부담 할인은 판매 금액의 일부다.
        if (request.platformDiscountOrZero() < 0 || request.platformDiscountOrZero() > request.salesAmount()) {
            throw new ServiceException(WalletErrorCode.WALLET_INVALID_AMOUNT);
        }
    }
}
