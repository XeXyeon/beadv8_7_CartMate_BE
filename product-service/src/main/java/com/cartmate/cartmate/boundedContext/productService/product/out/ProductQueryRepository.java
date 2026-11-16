package com.cartmate.cartmate.boundedContext.productService.product.out;

import com.cartmate.cartmate.boundedContext.productService.product.domain.Product;

import java.util.List;
import java.util.Optional;

public interface ProductQueryRepository {

    // 상품 단건 삭제: 상품을 영속성 컨텍스트에 올리지 않고 판매글 ID만 읽는다(뒤의 잠금 조회가 최신 상태로 읽어 오게)
    Optional<Long> findSalesPostIdById(Long id);

    // 상품 단건 삭제: 같은 판매글의 상품을 잠근다(비관적 쓰기 잠금). 동시에 두 상품을 지워 판매글에 상품이 0개가 되는 걸 막는다
    List<Product> findForUpdateBySalesPostId(Long salesPostId);
}
