package com.cartmate.cartmate.boundedContext.orderService.cart.out;

import java.util.List;

public record CartProductOptions(
        Long productId,
        Long sellerMemberId,   // 판매자 본인의 회원 ID. 자기 상품 구매를 막는 데 쓴다
        String productName,
        String imageUrl,
        boolean purchasable,
        List<Option> options
) {

    public record Option(
            Long inventoryId,
            String optionName,
            int price,
            boolean purchasable
    ) {
    }
}
