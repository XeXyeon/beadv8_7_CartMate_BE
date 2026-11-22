package com.cartmate.cartmate.boundedContext.productService.product.dto;

/**
 * 재고 하나의 수량만. 판매글 상세가 남은 수량을 매번 새로 읽을 때 쓴다(재고 이름 · 가격 · 옵션은 판매글 쪽이 캐시).
 *
 * @param stockQuantity    실제 재고
 * @param reservedQuantity 선점된 수량 (남은 수량 = 실제 재고 - 선점)
 */
public record InventoryStock(Long inventoryId, Integer stockQuantity, Integer reservedQuantity) {
}
