package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

public interface SalesPostProductWishQueryRepository {

    // 회원의 찜을 찜한 순서(최근 찜 먼저)로. 목록에서 빠지는 상태(삭제된 판매글 상품 · 삭제(중) · 등록 중인 판매글)는
    // 쿼리에서 미리 뺀다 (그래야 페이지마다 개수와 전체 개수가 맞는다)
    Page<WishRow> findMyWishes(Long memberId, Pageable pageable);

    // 판매글 상품들의 대표 이미지 s3Key (활성 이미지 중 순서가 가장 앞선 것). 이미지가 없는 상품은 키가 없다
    Map<Long, String> findThumbnailS3Keys(Collection<Long> productIds);

    // 주어진 판매글 상품들 중 이 회원이 찜한 상품 id (판매글 상세의 찜 표시)
    Set<Long> findWishedProductIds(Long memberId, Collection<Long> productIds);

    record WishRow(
            Long wishId,
            LocalDateTime wishedAt,
            Long productId,
            String productName,
            Long basePrice,
            SalesPostProductStatus productStatus,
            Long salesPostId,
            SalesPostSaleStatus postStatus
    ) {
    }
}
