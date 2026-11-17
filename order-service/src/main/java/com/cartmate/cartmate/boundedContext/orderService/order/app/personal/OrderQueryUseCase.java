package com.cartmate.cartmate.boundedContext.orderService.order.app.personal;

import com.cartmate.cartmate.boundedContext.orderService.order.domain.buyercancel.BuyerCancelRequest;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.buyercancel.BuyerCancelRequestItem;
import com.cartmate.cartmate.boundedContext.orderService.order.out.buyercancel.BuyerCancelRequestRepository;
import java.util.HashMap;
import java.util.Map;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.Order;
import com.cartmate.cartmate.shared.order.OrderStatus;
import com.cartmate.cartmate.boundedContext.orderService.order.dto.personal.OrderDetailResponse;
import com.cartmate.cartmate.boundedContext.orderService.order.dto.personal.OrderSummaryResponse;
import com.cartmate.cartmate.boundedContext.orderService.order.exception.OrderErrorCode;
import com.cartmate.cartmate.boundedContext.orderService.order.out.personal.OrderRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
class OrderQueryUseCase {

    private final OrderRepository orderRepository;
    private final OrderSupport orderSupport;
    private final BuyerCancelRequestRepository buyerCancelRequestRepository;

    List<OrderSummaryResponse> getMyList(Long memberId) {
        return orderRepository.findAllByMember(memberId).stream()
                .map(OrderSummaryResponse::from)
                .toList();
    }

    OrderDetailResponse getDetail(Long memberId, Long orderId) {
        Order order = orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new ServiceException(OrderErrorCode.ORDER_NOT_FOUND));
        orderSupport.validateBuyer(order, memberId);
        return OrderDetailResponse.from(order, latestBuyerCancelItems(orderId));
    }

    // 상품마다 그 상품이 들어 있는 가장 최근 구매자 취소 요청 줄. 처리 중인지와 거절 사유를 상품별로 보여 주려고 쓴다
    private Map<Long, BuyerCancelRequestItem> latestBuyerCancelItems(Long orderId) {
        Map<Long, BuyerCancelRequestItem> latest = new HashMap<>();
        for (BuyerCancelRequest request : buyerCancelRequestRepository.findWithItemsByOrderId(orderId)) {
            for (BuyerCancelRequestItem item : request.getItems()) {
                latest.merge(item.getOrderItemId(), item,
                        (kept, other) -> kept.getRequest().getId() >= other.getRequest().getId() ? kept : other);
            }
        }
        return latest;
    }

    boolean hasActiveIndividualGroupOrder(Long individualGroupPurchaseId, Long productId) {
        return orderRepository.existsActiveOrderByIndividualGroupPurchaseAndProduct(individualGroupPurchaseId, productId);
    }

    long countPaymentPendingOfBuyer(Long buyerMemberId) {
        return orderRepository.countByBuyerMemberIdAndStatus(buyerMemberId, OrderStatus.PAYMENT_PENDING);
    }
}
