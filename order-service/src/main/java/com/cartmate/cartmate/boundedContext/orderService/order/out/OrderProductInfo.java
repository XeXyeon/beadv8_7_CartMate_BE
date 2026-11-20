package com.cartmate.cartmate.boundedContext.orderService.order.out;

public record OrderProductInfo(
        Long inventoryId,
        Long productId,
        Long sellerId,
        Long sellerMemberId,   // 판매자 본인의 회원 ID. 자기 상품 구매를 막는 데 쓴다
        String productName,
        String optionName,
        long price,
        String imageUrl,
        boolean purchasable,   // 판매 중 + 재고 삭제 안 됨 + 남은 수량 1개 이상
        boolean onSale         // 판매 중 + 재고 삭제 안 됨 (남은 수량은 안 본다)
) {
}
