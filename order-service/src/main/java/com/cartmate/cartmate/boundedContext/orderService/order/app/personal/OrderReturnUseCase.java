package com.cartmate.cartmate.boundedContext.orderService.order.app.personal;

import com.cartmate.cartmate.boundedContext.orderService.order.domain.claimreturn.OrderItemReturn;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.itemcancel.CouponMode;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.Order;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.OrderReturnOutcome;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.OrderReturnPreview;
import com.cartmate.cartmate.boundedContext.orderService.order.exception.OrderErrorCode;
import com.cartmate.cartmate.boundedContext.orderService.order.out.OrderCouponClient;
import com.cartmate.cartmate.boundedContext.orderService.order.out.claimreturn.OrderItemReturnRepository;
import com.cartmate.cartmate.boundedContext.orderService.order.out.personal.OrderRepository;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.shared.delivery.event.DeliveryClaimReturnApprovedEvent;
import com.cartmate.cartmate.shared.delivery.event.DeliveryClaimReturnRequestedEvent;
import com.cartmate.cartmate.shared.order.event.OrderClaimRefundRequestedEvent;
import com.cartmate.cartmate.shared.order.event.OrderClaimReturnVerifiedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;

/**
 * 반품: 배송 서버의 접수 검사 요청에 답하고, 승인된 반품의 환불 금액을 쿠폰 할인을 반영해 정해 결제 서버에 요청한다.
 */
@Service
@RequiredArgsConstructor
class OrderReturnUseCase {

    private final OrderRepository orderRepository;
    private final OrderSupport orderSupport;
    private final DomainEventPublisher eventPublisher;
    private final OrderCouponClient orderCouponClient;
    private final OrderItemReturnRepository orderItemReturnRepository;
    private final Clock clock;

    // 구매자 사정 반품은 남은 상품으로 쿠폰을 다시 계산해 환불이 음수가 되면 거절한다.
    // 도메인이 던진 예외는 이 안에서 잡아 거절 사유로 답한다(트랜잭션 경계를 넘지 않아 롤백 전용으로 표시되지 않는다).
    void verifyReturn(DeliveryClaimReturnRequestedEvent event) {
        String rejectCode = null;
        try {
            // 주문을 찾는 것도 이 안에서 한다. 못 찾아도 접수 불가로 답해야 클레임이 확인 중에 멈추지 않는다.
            Order order = orderSupport.getOrderForUpdate(event.orderId());
            // 같은 주문에서 처리 중인 다른 반품이 먼저 환불된다고 보고 계산한다.
            if (order.previewReturn(event.orderItemIds(), event.pendingReturns(), CouponMode.RECALCULATE).underpaid()) {
                rejectCode = OrderErrorCode.ORDER_RETURN_COUPON_MINIMUM.resultCode();
            }
        } catch (ServiceException e) {
            rejectCode = e.getResultCode();
        }
        eventPublisher.publish(new OrderClaimReturnVerifiedEvent(event.claimId(), rejectCode == null, rejectCode));
    }

    // 환불이 0원이어도 결과 이벤트를 받을 수 있게 환불 요청은 항상 낸다.
    void applyReturn(DeliveryClaimReturnApprovedEvent event) {
        Order order = orderSupport.getOrderForUpdate(event.orderId());
        // 먼저 잠근 뒤 확인해야 같은 이벤트가 동시에 처리돼도 한 번만 반영된다
        if (orderItemReturnRepository.existsByClaimId(event.claimId())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        OrderReturnOutcome outcome = order.applyReturn(event.orderItemIds(),
                event.sellerFault() ? CouponMode.PROPORTIONAL : CouponMode.RECALCULATE, now);
        orderItemReturnRepository.save(OrderItemReturn.of(event.claimId(), order.getId(), outcome.refundAmount(), now));
        eventPublisher.publish(new OrderClaimRefundRequestedEvent(
                event.claimId(), order.getId(), order.getBuyerMemberId(), outcome.refundAmount()));
        if (outcome.couponReturnable()) {
            orderCouponClient.restore(order.getBuyerMemberId(), order.getMemberCouponId(), order.getId());
        }
    }

    // 구매자 예상 환불. 판매자 탓 사유면 비율, 아니면 쿠폰 재계산이다. 보여 주기용이라 실제 환불은 승인 이벤트로 다시 계산한다.
    OrderReturnPreview previewBuyerReturn(Long memberId, Long orderId, Collection<Long> orderItemIds, boolean sellerFault) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ServiceException(OrderErrorCode.ORDER_NOT_FOUND));
        return order.previewBuyerReturn(memberId, orderItemIds,
                sellerFault ? CouponMode.PROPORTIONAL : CouponMode.RECALCULATE);
    }
}
