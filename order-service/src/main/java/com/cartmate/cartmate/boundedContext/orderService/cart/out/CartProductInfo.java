package com.cartmate.cartmate.boundedContext.orderService.cart.out;

public record CartProductInfo(
        Long inventoryId,
        Long productId,
        Long sellerMemberId,   // 판매자 본인의 회원 ID. 자기 상품 구매를 막는 데 쓴다
        String productName,
        String optionName,
        int price,
        String imageUrl,
        boolean purchasable
) {
}
