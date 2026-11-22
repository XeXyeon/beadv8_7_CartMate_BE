package com.cartmate.cartmate.boundedContext.productService.post.dto;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 판매글 상세에 붙일 상품별 선택 목록 · 재고.
 * 잘 안 바뀌는 부분(재고 구성 · 이름 · 가격 · 상품옵션)만 Redis에 캐시하고(이때 수량은 비어 있다),
 * 남은 수량은 상세를 볼 때마다 상품에서 새로 읽어 withStocks로 붙인다.
 */
public record SalesPostDetailInventories(List<ProductInventories> products) {

    public record ProductInventories(
            Long productId,
            List<SelectSalesPostOptionGroup> optionGroups,
            List<SelectSalesPostInventory> inventories
    ) {
    }

    public Map<Long, ProductInventories> byProductId() {
        return products.stream().collect(Collectors.toMap(ProductInventories::productId, Function.identity()));
    }

    // 캐시된 재고 구성. 매번 읽는 수량의 재고 ID들과 다르면(판매글 수정으로 재고가 추가 · 삭제됨) 캐시가 예전 것이다
    public Set<Long> inventoryIds() {
        return products.stream()
                .flatMap(product -> product.inventories().stream())
                .map(SelectSalesPostInventory::inventoryId)
                .collect(Collectors.toSet());
    }

    // 재고마다 남은 수량을 붙인 사본. 수량을 못 받은 재고는 0(품절)으로 보여 준다
    public SalesPostDetailInventories withStocks(Map<Long, Integer> availableByInventoryId) {
        return new SalesPostDetailInventories(products.stream()
                .map(product -> new ProductInventories(product.productId(), product.optionGroups(),
                        product.inventories().stream()
                                .map(inventory -> inventory.withStockQuantity(
                                        availableByInventoryId.getOrDefault(inventory.inventoryId(), 0)))
                                .toList()))
                .toList());
    }
}
