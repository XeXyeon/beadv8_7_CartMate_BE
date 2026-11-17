package com.cartmate.cartmate.boundedContext.productService.coupon.out;

import com.cartmate.cartmate.boundedContext.productService.coupon.domain.MemberCoupon;
import com.cartmate.cartmate.boundedContext.productService.coupon.domain.MemberCouponStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// 보유 쿠폰 ID · 쿠폰별 개수 · 잠금 조회는 MemberCouponQueryRepository(QueryDSL)
public interface MemberCouponRepository extends JpaRepository<MemberCoupon, Long>, MemberCouponQueryRepository {

    boolean existsByCouponIdAndMemberId(Long couponId, Long memberId);

    List<MemberCoupon> findAllByMemberIdOrderByIdDesc(Long memberId);

    // 결제 화면 쿠폰 목록: 아직 쓰지 않은 쿠폰 (보유·사용 예정)
    List<MemberCoupon> findAllByMemberIdAndStatusNotOrderByIdDesc(Long memberId, MemberCouponStatus status);
}
