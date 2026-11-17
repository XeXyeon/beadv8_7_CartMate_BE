package com.cartmate.cartmate.shared.delivery.event;

import java.util.List;

/**
 * 같은 주문에서 이미 처리 중인 다른 반품 클레임 하나. 반품 접수 검사가 이 반품이 먼저 승인돼 환불된 것으로 보고 계산한다.
 * 서로를 모른 채 각각 검사를 통과한 반품들이 차례로 환불되면, 합쳐서 신청했을 때보다 더 돌려받을 수 있기 때문이다.
 *
 * @param sellerFault  판매자 탓 사유면 할인을 비율로, 아니면 남은 상품으로 쿠폰을 다시 계산해 환불된다.
 * @param orderItemIds 그 클레임이 반품하려는 주문 항목
 */
public record PendingReturn(
        boolean sellerFault,
        List<Long> orderItemIds
) {
}
