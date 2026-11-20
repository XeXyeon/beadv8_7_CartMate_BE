package com.cartmate.cartmate.boundedContext.orderService.order.in.groupshared;

import com.cartmate.cartmate.boundedContext.orderService.order.app.OrderPaymentRetryService;
import com.cartmate.cartmate.boundedContext.orderService.order.app.groupshared.SharedGroupOrderFacade;
import com.cartmate.cartmate.boundedContext.orderService.order.dto.groupshared.SharedGroupOrderDetailResponse;
import com.cartmate.cartmate.boundedContext.orderService.order.dto.groupshared.SharedGroupOrderSummaryResponse;
import com.cartmate.cartmate.boundedContext.orderService.order.dto.groupshared.SharedGroupPaymentRequest;
import com.cartmate.cartmate.global.rsData.RsData;
import com.cartmate.cartmate.global.security.SecurityUser;
import com.cartmate.cartmate.shared.coupon.dto.CouponOption;
import com.cartmate.cartmate.shared.order.dto.TeamDeliveryAddress;
import com.cartmate.cartmate.shared.payment.dto.PaymentResult;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/group-orders/shared")
@RequiredArgsConstructor
public class SharedGroupOrderController {

    private final SharedGroupOrderFacade sharedGroupOrderFacade;
    private final OrderPaymentRetryService orderPaymentRetryService;

    @GetMapping("/me")
    public RsData<List<SharedGroupOrderSummaryResponse>> getMyList(@AuthenticationPrincipal SecurityUser user) {
        return new RsData<>("200-1", "내 공동 주문 목록 조회 성공했습니다.", sharedGroupOrderFacade.getMyList(user.memberId()));
    }

    @GetMapping(params = "purchaseId")
    @Operation(operationId = "getSharedGroupOrderByPurchase", summary = "공동구매 id로 내 공동 주문 상세 조회")
    public RsData<SharedGroupOrderDetailResponse> getDetailByPurchase(
            @AuthenticationPrincipal SecurityUser user,
            @RequestParam Long purchaseId
    ) {
        return new RsData<>("200-1", "공동 주문 조회 성공했습니다.", sharedGroupOrderFacade.getDetailByPurchase(user.memberId(), purchaseId));
    }

    @GetMapping("/{id}")
    public RsData<SharedGroupOrderDetailResponse> getDetail(
            @AuthenticationPrincipal SecurityUser user,
            @PathVariable Long id
    ) {
        return new RsData<>("200-1", "공동 주문 조회 성공했습니다.", sharedGroupOrderFacade.getDetail(user.memberId(), id));
    }

    @PostMapping("/{id}/cancel")
    public RsData<Void> cancel(
            @AuthenticationPrincipal SecurityUser user,
            @PathVariable Long id
    ) {
        sharedGroupOrderFacade.cancelByCreator(user.memberId(), id);
        return new RsData<>("200-1", "공동 주문을 취소했습니다.");
    }

    @PostMapping("/{id}/payment")
    @Operation(operationId = "paySharedGroupOrder", summary = "공동 주문 분담금 예치금 결제 (플랫폼 쿠폰 선택)")
    public RsData<PaymentResult> pay(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id,
                                     @Valid @RequestBody(required = false) SharedGroupPaymentRequest request) {
        TeamDeliveryAddress address = request == null ? null : request.teamDeliveryAddress();
        Long memberCouponId = request == null ? null : request.memberCouponId();
        return new RsData<>("200-1", "분담금 결제가 완료되었습니다.",
                orderPaymentRetryService.paySharedGroupOrder(user.memberId(), id, address, memberCouponId));
    }

    @GetMapping("/{id}/coupons")
    @Operation(operationId = "sharedGroupOrderCouponOptions",
            summary = "분담금 결제에 쓸 수 있는 내 쿠폰 목록 (대상 금액은 내 분담금, 판매자 쿠폰은 쓸 수 없음)")
    public RsData<List<CouponOption>> couponOptions(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id) {
        return new RsData<>("200-1", "쿠폰 목록 조회 성공했습니다.", sharedGroupOrderFacade.couponOptions(user.memberId(), id));
    }
}
