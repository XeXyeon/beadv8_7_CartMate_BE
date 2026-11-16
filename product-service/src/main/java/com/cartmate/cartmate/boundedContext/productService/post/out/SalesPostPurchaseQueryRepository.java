package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostPurchase;

import java.time.LocalDateTime;
import java.util.List;

public interface SalesPostPurchaseQueryRepository {

    // 리뷰를 쓸 수 있는 구매 기록: 이 회원이 구매자, 구매확정 시각이 since 이후(기한 안), 리뷰가 없음(삭제된 리뷰 포함).
    // 기한이 가까운(구매확정이 오래된) 순서
    List<SalesPostPurchase> findWritable(Long memberId, LocalDateTime since);
}
