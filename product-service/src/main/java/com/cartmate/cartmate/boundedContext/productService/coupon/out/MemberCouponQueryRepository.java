package com.cartmate.cartmate.boundedContext.productService.coupon.out;

import com.cartmate.cartmate.boundedContext.productService.coupon.domain.MemberCoupon;
import com.cartmate.cartmate.boundedContext.productService.coupon.domain.MemberCouponStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MemberCouponQueryRepository {

    // 다운로드 목록에서 "이미 받음" 표시용
    List<Long> findOwnedCouponIds(Long memberId, Collection<Long> couponIds);

    // 관리자 목록: 쿠폰별 사용 완료 수 (쿠폰 수만큼 쿼리하지 않고 한 번에). 행마다 [쿠폰 ID(Long), 개수(Long)]
    List<Object[]> countByCouponIdsAndStatus(Collection<Long> couponIds, MemberCouponStatus status);

    // 주문에 쓸 때 잠근다(비관적 쓰기 잠금): 같은 쿠폰을 두 주문에 동시에 쓰지 못하게
    Optional<MemberCoupon> findByIdForUpdate(Long id);
}
