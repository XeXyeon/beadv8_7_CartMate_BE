package com.cartmate.cartmate.boundedContext.productService.coupon.out;

import com.cartmate.cartmate.boundedContext.productService.coupon.domain.Coupon;
import com.cartmate.cartmate.boundedContext.productService.coupon.domain.CouponAutoTrigger;
import com.cartmate.cartmate.boundedContext.productService.coupon.domain.CouponIssueType;
import com.cartmate.cartmate.boundedContext.productService.coupon.domain.CouponStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// 발급 수 조건부 증가는 CouponQueryRepository(QueryDSL)
public interface CouponRepository extends JpaRepository<Coupon, Long>, CouponQueryRepository {

    List<Coupon> findAllByIssueTypeAndAutoTriggerAndStatus(CouponIssueType issueType, CouponAutoTrigger autoTrigger,
                                                           CouponStatus status);

    List<Coupon> findAllByIssueTypeAndStatusOrderByIdDesc(CouponIssueType issueType, CouponStatus status);

    Page<Coupon> findAllByOrderByIdDesc(Pageable pageable);
}
