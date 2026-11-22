package com.cartmate.cartmate.boundedContext.productService.post.dto;

/**
 * 상품에서 받은 재고 하나의 수량. 판매글 상세가 남은 수량을 매번 새로 붙일 때 쓴다(캐시하지 않는다).
 *
 * @param stockQuantity    실제 재고
 * @param reservedQuantity 선점된 수량
 */
public record SelectInventoryStock(Long inventoryId, Integer stockQuantity, Integer reservedQuantity) {

    // 구매 가능한 수량 = 실제 재고 - 선점 (0 미만이면 0)
    public int availableQuantity() {
        int stock = stockQuantity == null ? 0 : stockQuantity;
        int reserved = reservedQuantity == null ? 0 : reservedQuantity;
        return Math.max(stock - reserved, 0);
    }
}
