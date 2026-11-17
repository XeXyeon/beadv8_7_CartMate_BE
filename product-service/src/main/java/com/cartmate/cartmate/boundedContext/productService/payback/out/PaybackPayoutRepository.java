package com.cartmate.cartmate.boundedContext.productService.payback.out;

import com.cartmate.cartmate.boundedContext.productService.payback.domain.PaybackPayout;
import com.cartmate.cartmate.boundedContext.productService.payback.domain.PaybackPayoutStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// 잠금 조회 · 상태별 ID 조회는 PaybackPayoutQueryRepository(QueryDSL)
public interface PaybackPayoutRepository extends JpaRepository<PaybackPayout, Long>, PaybackPayoutQueryRepository {

    List<PaybackPayout> findAllByStatusOrderByIdDesc(PaybackPayoutStatus status, Limit limit);
}
