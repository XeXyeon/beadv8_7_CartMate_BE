package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectSalesPostRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface SalesPostQueryRepository {
    Page<SalesPost> selectSalesPostAll(SelectSalesPostRequest request, Pageable pageable);

    // 판매자 본인의 판매글(삭제된 글만 뺀다). 최신순
    Page<SalesPost> selectBySeller(Long sellerId, Pageable pageable);

    Long changeUpdateStatus(Long salesPostId, Collection<SalesPostUpdateStatus> from,
                            SalesPostUpdateStatus to, LocalDateTime now);

    Long startUpdate(Long salesPostId, Collection<SalesPostUpdateStatus> from, LocalDateTime now);

    Long startDelete(Long salesPostId, Collection<SalesPostUpdateStatus> editable);

    // 삭제 대사: DELETING인 판매글의 삭제 시작 시각(modifyDate)을 지금으로 다시 잡는다. DELETING이 아니면 0
    Long refreshDeleting(Long salesPostId, LocalDateTime now);

    Page<SalesPost> selectForAdmin(SalesPostSaleStatus status, String keyword, Pageable pageable);

    // 상태별 판매글 수를 한 번의 GROUP BY 조회로 (없는 상태는 결과에 없음)
    Map<SalesPostSaleStatus, Long> countByStatuses(Collection<SalesPostSaleStatus> statuses);

    Optional<SalesPost> findLatestById(Long id);

    // 회원 탈퇴 신청: 판매자의 특정 상태 판매글 ID, id 순서
    List<Long> findIdsBySellerIdAndStatus(Long sellerId, SalesPostSaleStatus status);

    // 리뷰 집계: 리뷰 수 · 별점 합계를 DB에서 바로 더하고 뺀다 (동시에 여러 리뷰가 쓰여도 어긋나지 않게). 바뀐 행 수
    // countDelta: 작성 +1, 삭제 -1, 수정 0 / ratingDelta: 작성 +별점, 삭제 -별점, 수정 (새 별점 - 옛 별점)
    // 실행 전 flush, 실행 뒤 영속성 컨텍스트를 비운다
    int applyReviewSummary(Long postId, long countDelta, long ratingDelta);
}
