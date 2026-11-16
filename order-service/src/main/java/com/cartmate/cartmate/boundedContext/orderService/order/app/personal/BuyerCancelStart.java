package com.cartmate.cartmate.boundedContext.orderService.order.app.personal;

import com.cartmate.cartmate.boundedContext.orderService.order.domain.buyercancel.BuyerCancelRequest;

import java.util.List;

// 구매자 취소를 시작한 결과: 결제 대기라 바로 취소했거나, 배송 서버에 물어볼 항목 목록
public record BuyerCancelStart(boolean canceledNow, List<BuyerCancelRequest.Target> targets) {

    static BuyerCancelStart canceled() {
        return new BuyerCancelStart(true, List.of());
    }

    static BuyerCancelStart request(List<BuyerCancelRequest.Target> targets) {
        return new BuyerCancelStart(false, targets);
    }
}
