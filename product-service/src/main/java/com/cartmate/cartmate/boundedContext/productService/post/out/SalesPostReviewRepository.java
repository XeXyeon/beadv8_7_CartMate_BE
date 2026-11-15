package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalesPostReviewRepository extends JpaRepository<SalesPostReview, Long> {

    // 이 구매 기록(주문항목)으로 쓴 리뷰가 있는지 (삭제된 리뷰 포함)
    boolean existsByPurchaseId(Long purchaseId);

    // 판매글의 리뷰 목록 (삭제된 리뷰 제외), 최신순
    Page<SalesPostReview> findBySalesPostIdAndIsDeletedFalseOrderByIdDesc(Long salesPostId, Pageable pageable);

    // 탈퇴한 회원이 쓴 리뷰의 작성자 닉네임을 한 번에 바꾼다 (별점 · 내용은 그대로). 바뀐 행 수를 돌려준다
    @Modifying(clearAutomatically = true)
    @Query("update SalesPostReview r set r.writerNickname = :nickname where r.writerMemberId = :memberId")
    int anonymizeWriter(@Param("memberId") Long memberId, @Param("nickname") String nickname);
}
