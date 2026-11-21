package com.cartmate.cartmate.shared.wallet.dto;

import com.cartmate.cartmate.shared.wallet.WalletPurchaseType;

public record SettleAndDepositRequest(
        Long payoutId,
        Long orderId,
        WalletPurchaseType purchaseType, // 개인 주문과 공동물품 주문은 주문 ID가 겹칠 수 있어 구분한다.
        Long sellerMemberId, // 입금받을 판매자의 회원 ID
        Long salesAmount,    // 판매 금액(판매자 부담 쿠폰 할인을 뺀 금액). 수수료의 기준이다.
        Long payoutAmount,   // 판매자에게 입금할 정산 금액(수수료 제외)
        // 판매 금액 중 플랫폼 부담 쿠폰 할인. 구매자가 내지 않은 돈이라 홀딩에는 없고 플랫폼 지갑이 낸다.
        // 홀딩에서 꺼내는 금액은 salesAmount - platformDiscountAmount 이다. null이면 0.
        Long platformDiscountAmount
) {
    public SettleAndDepositRequest(Long payoutId, Long orderId, WalletPurchaseType purchaseType,
                                   Long sellerMemberId, Long salesAmount, Long payoutAmount) {
        this(payoutId, orderId, purchaseType, sellerMemberId, salesAmount, payoutAmount, null);
    }

    public long platformDiscountOrZero() {
        return platformDiscountAmount == null ? 0L : platformDiscountAmount;
    }
}
