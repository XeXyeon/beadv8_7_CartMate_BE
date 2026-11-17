package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductWish;
import org.springframework.data.jpa.repository.JpaRepository;

// 내 찜 목록 · 대표 이미지 조회는 SalesPostProductWishQueryRepository(QueryDSL)
public interface SalesPostProductWishRepository extends JpaRepository<SalesPostProductWish, Long>, SalesPostProductWishQueryRepository {

    boolean existsByMemberIdAndSalesPostProductId(Long memberId, Long salesPostProductId);

    // 찜 해제. 찜하지 않은 상품이면 지울 줄이 없어 0을 돌려준다
    long deleteByMemberIdAndSalesPostProductId(Long memberId, Long salesPostProductId);
}
