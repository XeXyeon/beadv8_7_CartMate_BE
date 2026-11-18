package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

// 직접 쓰는 쿼리(판매자 판매글 ID · 리뷰 집계 등)는 SalesPostQueryRepository(QueryDSL)
public interface SalesPostRepository extends JpaRepository<SalesPost, Long>, SalesPostQueryRepository {
    // 판매글 숨김 대사: 최근 내려간 판매글
    List<SalesPost> findByStatusInAndHiddenAtAfter(Collection<SalesPostSaleStatus> statuses, LocalDateTime after);

    // 삭제 대사: 삭제 시작(modifyDate) 후 오래 지난 DELETING 판매글
    List<SalesPost> findByStatusAndModifyDateBeforeOrderByIdAsc(SalesPostSaleStatus status, LocalDateTime before, Limit limit);

    // 수정 복구 대사: 수정 상태(PENDING · RECOVERING)가 바뀐 지 오래된 판매글. afterId 다음부터 읽어,
    // 한 번 실행 안에서 이미 시도한(실패해 상태가 그대로인) 판매글을 다시 읽지 않고 다음으로 넘어간다
    List<SalesPost> findByUpdateStatusAndUpdateStatusChangedAtBeforeAndIdGreaterThanOrderByIdAsc(
            SalesPostUpdateStatus updateStatus, LocalDateTime before, Long afterId, Limit limit);

    List<SalesPost> findByStatusAndCreateDateBeforeOrderByIdAsc(
            SalesPostSaleStatus status, LocalDateTime before, Limit limit);

    // 검색 재색인: 판매글마다 상품을 따로 읽지 않도록 상품까지 한 번에 가져온다
    @EntityGraph(attributePaths = "productList")
    List<SalesPost> findWithProductsByIdIn(Collection<Long> ids);
}
