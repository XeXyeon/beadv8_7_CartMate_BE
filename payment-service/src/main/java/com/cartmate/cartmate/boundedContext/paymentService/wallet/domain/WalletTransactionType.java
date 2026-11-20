package com.cartmate.cartmate.boundedContext.paymentService.wallet.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum WalletTransactionType {
    CHARGE("충전"),             // 예치금 충전
    USE("구매"),                // 상품 구매
    REFUND("환불"),             // 상품 결제 취소에 따른 예치금 복구
    SETTLEMENT("정산금 입금"),  // 판매 정산금 입금
    FEE("수수료"),              // 판매 수수료 수입 (플랫폼 지갑)
    COUPON_SUBSIDY("쿠폰 할인 지원"), // 플랫폼 부담 쿠폰 할인을 정산 때 판매자에게 대신 낸 돈 (플랫폼 지갑에서 빠짐)
    WITHDRAW("출금"),           // 예치금 출금 (신청 때 차감)
    WITHDRAW_RESTORE("출금 취소"); // 출금 실패·취소로 잔액 복구

    private final String label;

    // 플랫폼 지갑에만 생기는 거래. 플랫폼 지갑은 쿠폰 할인 지원 때문에 잔액이 음수일 수 있다(실제 돈은 회사 계좌가 낸다).
    public boolean allowsNegativeBalance() {
        return this == FEE || this == COUPON_SUBSIDY;
    }
}
