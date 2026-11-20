package com.cartmate.cartmate.boundedContext.paymentService.wallet.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// PENDING → PROCESSING → COMPLETED / FAILED, PENDING → CANCELED. 잔액은 신청 때 빠지고 FAILED·CANCELED가 되면 돌아온다
@Getter
@RequiredArgsConstructor
public enum WithdrawStatus {
    PENDING("출금 대기"),
    PROCESSING("출금 처리 중"),     // 이체를 요청했고 결과를 아직 모름 (돈이 나갔을 수 있다 → 회원 취소 불가)
    COMPLETED("출금 완료"),
    CANCELED("출금 취소"),
    FAILED("출금 실패");

    private final String label;
}
