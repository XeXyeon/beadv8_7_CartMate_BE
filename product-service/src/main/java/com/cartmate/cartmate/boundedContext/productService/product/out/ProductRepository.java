package com.cartmate.cartmate.boundedContext.productService.product.out;

import com.cartmate.cartmate.boundedContext.productService.product.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// 판매글 ID만 읽기 · 같은 판매글 상품 잠금 조회는 ProductQueryRepository(QueryDSL)
public interface ProductRepository extends JpaRepository<Product, Long>, ProductQueryRepository {
    List<Product> findBySalesPostId(Long salesPostId);
}
