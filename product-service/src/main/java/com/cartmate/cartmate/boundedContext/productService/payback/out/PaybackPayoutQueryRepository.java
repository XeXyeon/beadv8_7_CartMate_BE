package com.cartmate.cartmate.boundedContext.productService.payback.out;

import com.cartmate.cartmate.boundedContext.productService.payback.domain.PaybackPayout;
import com.cartmate.cartmate.boundedContext.productService.payback.domain.PaybackPayoutStatus;
import org.springframework.data.domain.Limit;

import java.util.List;
import java.util.Optional;

public interface PaybackPayoutQueryRepository {

    // 비관적 쓰기 잠금으로 읽는다
    Optional<PaybackPayout> findByIdForUpdate(Long id);

    // 상태별 ID, id 순서. limit만큼만 (Limit.unlimited()면 전부)
    List<Long> findIdsByStatus(PaybackPayoutStatus status, Limit limit);
}
