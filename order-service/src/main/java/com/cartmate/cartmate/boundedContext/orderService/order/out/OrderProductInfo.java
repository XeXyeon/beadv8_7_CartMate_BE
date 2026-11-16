package com.cartmate.cartmate.boundedContext.orderService.order.out;

public record OrderProductInfo(
        Long inventoryId,
        Long productId,
        Long sellerId,
        String productName,
        String optionName,
        long price,
        String imageUrl,
        boolean purchasable,   // 판매 중 + 재고 삭제 안 됨 + 남은 수량 1개 이상
        boolean onSale         // 판매 중 + 재고 삭제 안 됨 (남은 수량은 안 본다)
) {
}
