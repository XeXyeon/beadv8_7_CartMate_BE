package com.cartmate.cartmate.boundedContext.orderService.order.in.personal;

import org.springframework.web.bind.annotation.RequestParam;
import com.cartmate.cartmate.shared.coupon.dto.CouponOption;
import com.cartmate.cartmate.boundedContext.orderService.order.dto.personal.OrderPaymentRequest;
import com.cartmate.cartmate.boundedContext.orderService.order.app.OrderPaymentRetryService;
import com.cartmate.cartmate.boundedContext.orderService.order.app.buyercancel.BuyerCancelFacade;
import com.cartmate.cartmate.boundedContext.orderService.order.app.personal.OrderFacade;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.BuyerCancelResult;
import com.cartmate.cartmate.boundedContext.orderService.order.dto.personal.*;
import com.cartmate.cartmate.global.rsData.RsData;
import com.cartmate.cartmate.global.security.SecurityUser;
import com.cartmate.cartmate.shared.payment.dto.PaymentResult;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderFacade orderFacade;
    private final OrderPaymentRetryService orderPaymentRetryService;
    private final BuyerCancelFacade buyerCancelFacade;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<OrderCreateResponse> createPersonal(
            @AuthenticationPrincipal SecurityUser user,
            @Valid @RequestBody OrderCreateRequest request
    ) {
        return new RsData<>("201-1", "주문을 만들었습니다.", orderFacade.createPersonal(user.memberId(), request));
    }

    // 바로구매: 장바구니를 거치지 않고 상품 하나를 주문한다. 결제해도 장바구니는 바뀌지 않는다
    @PostMapping("/direct")
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<OrderCreateResponse> createDirect(
            @AuthenticationPrincipal SecurityUser user,
            @Valid @RequestBody DirectOrderCreateRequest request
    ) {
        return new RsData<>("201-1", "주문을 만들었습니다.", orderFacade.createDirect(user.memberId(), request));
    }

    @PostMapping("/individual-group")
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<OrderCreateResponse> createIndividualGroup(
            @AuthenticationPrincipal SecurityUser user,
            @Valid @RequestBody IndividualGroupOrderCreateRequest request
    ) {
        return new RsData<>("201-1", "공동구매 상품을 주문했습니다.", orderFacade.createIndividualGroup(user.memberId(), request));
    }

    @GetMapping("/me")
    public RsData<List<OrderSummaryResponse>> getMyList(@AuthenticationPrincipal SecurityUser user) {
        return new RsData<>("200-1", "내 주문 목록 조회 성공했습니다.", orderFacade.getMyList(user.memberId()));
    }

    @GetMapping("/{id}")
    public RsData<OrderDetailResponse> getDetail(
            @AuthenticationPrincipal SecurityUser user,
            @PathVariable Long id
    ) {
        return new RsData<>("200-1", "주문 조회 성공했습니다.", orderFacade.getDetail(user.memberId(), id));
    }

    @PostMapping("/{id}/cancel")
    public RsData<Void> cancel(
            @AuthenticationPrincipal SecurityUser user,
            @PathVariable Long id,
            @RequestBody(required = false) OrderCancelRequest request
    ) {
        List<Long> orderItemIds = request == null ? null : request.orderItemIds();
        if (buyerCancelFacade.request(user.memberId(), id, orderItemIds) == BuyerCancelResult.CANCELED) {
            return new RsData<>("200-1", "주문을 취소했습니다.");
        }
        return new RsData<>("200-1", "취소 요청을 접수했습니다. 판매자 배송 확인 후 취소됩니다.");
    }

    @GetMapping("/{id}/cancel-preview")
    @Operation(operationId = "cancelPreview", summary = "고른 상품을 취소하면 돌려받을 예상 환불 금액과 쿠폰 반환 여부")
    public RsData<OrderCancelPreviewResponse> cancelPreview(
            @AuthenticationPrincipal SecurityUser user,
            @PathVariable Long id,
            @RequestParam List<Long> orderItemIds
    ) {
        return new RsData<>("200-1", "예상 환불 금액 조회 성공했습니다.",
                OrderCancelPreviewResponse.from(orderFacade.previewBuyerCancel(user.memberId(), id, orderItemIds)));
    }

    @GetMapping("/{id}/return-preview")
    @Operation(operationId = "returnPreview", summary = "고른 상품을 반품하면 돌려받을 예상 환불 금액 (sellerFault는 더 이상 금액에 영향이 없다 — 쿠폰 할인은 결제 때 상품별로 고정)")
    public RsData<OrderReturnPreviewResponse> returnPreview(
            @AuthenticationPrincipal SecurityUser user,
            @PathVariable Long id,
            @RequestParam List<Long> orderItemIds,
            // 프론트 호환용으로 받기만 한다
            @RequestParam(defaultValue = "false") boolean sellerFault
    ) {
        return new RsData<>("200-1", "예상 환불 금액 조회 성공했습니다.",
                OrderReturnPreviewResponse.from(orderFacade.previewBuyerReturn(user.memberId(), id, orderItemIds)));
    }

    @PostMapping("/{orderId}/payment")
    @Operation(operationId = "payOrder", summary = "개인 주문 예치금 결제 (쿠폰 선택)")
    public RsData<PaymentResult> pay(@AuthenticationPrincipal SecurityUser user, @PathVariable Long orderId,
                                     @RequestBody(required = false) OrderPaymentRequest request) {
        Long memberCouponId = request == null ? null : request.memberCouponId();
        return new RsData<>("200-1", "결제가 완료되었습니다.",
                orderPaymentRetryService.payOrder(user.memberId(), orderId, memberCouponId));
    }

    @GetMapping("/{orderId}/payment-preview")
    @Operation(operationId = "preview", summary = "개인 주문 결제 미리보기 (금액·쿠폰 할인·결제 가능 여부·예치금 잔액)")
    public RsData<OrderPaymentPreviewResponse> paymentPreview(@AuthenticationPrincipal SecurityUser user,
                                                              @PathVariable Long orderId,
                                                              @RequestParam(required = false) Long memberCouponId) {
        return new RsData<>("200-1", "결제 정보 조회 성공",
                orderFacade.getPaymentPreview(user.memberId(), orderId, memberCouponId));
    }

    @GetMapping("/{orderId}/coupons")
    @Operation(operationId = "getOrderCouponOptions", summary = "결제 화면 쿠폰 목록 (쓸 수 있는 쿠폰과 예상 할인, 못 쓰는 쿠폰은 이유)")
    public RsData<List<CouponOption>> couponOptions(@AuthenticationPrincipal SecurityUser user, @PathVariable Long orderId) {
        return new RsData<>("200-1", "쿠폰 목록 조회 성공", orderFacade.getCouponOptions(user.memberId(), orderId));
    }
}
