package com.cartmate.cartmate.boundedContext.productService.product.out;

import com.cartmate.cartmate.boundedContext.productService.product.dto.InventoryStock;

import java.util.List;

// 재고 수량 조회와, 재고 수량을 DB에서 바로 바꾸는 조건부 UPDATE.
// UPDATE는 바뀐 행 수를 돌려주고, 실행 전에 영속성 컨텍스트를 flush하고 실행 뒤 비운다(이미 불러온 Inventory의 옛 수량을 다시 쓰지 않게)
public interface InventoryQueryRepository {

    // 판매글의 판매 중인 재고 수량만 읽는다(엔티티 없이 칸 3개, 쿼리 1번). 삭제된 상품 · 재고는 뺀다(selectProductsByPost와 같은 기준)
    List<InventoryStock> findStocksBySalesPostId(Long salesPostId);

    // 선점: 남은 수량(재고 - 선점)이 충분할 때만 선점 수량을 늘린다. 바뀐 행 수 0 = 부족
    // UPDATE 하는 동안 DB가 이 행을 잠가서 동시에 선점해도 초과 판매가 생기지 않는다(별도 락 없음)
    int reserve(Long id, int quantity);

    // 선점 해제: 선점 수량을 줄인다. 바뀐 행 수 0 = 선점 수량이 모자람(기록과 어긋남)
    int release(Long id, int quantity);

    // 판매 확정: 선점했던 수량을 재고와 선점에서 함께 뺀다(남은 수량은 그대로). 바뀐 행 수 0 = 기록과 어긋남
    int confirm(Long id, int quantity);

    // 재고 복구: 결제 후 취소된 수량을 재고에 되돌린다
    int restore(Long id, int quantity);
}
