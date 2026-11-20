package com.cartmate.cartmate.boundedContext.orderService.order.app.personal;

import com.cartmate.cartmate.boundedContext.orderService.order.out.OrderCouponClient;
import lombok.extern.slf4j.Slf4j;

import com.cartmate.cartmate.boundedContext.orderService.order.exception.OrderErrorCode;

import com.cartmate.cartmate.global.exception.ServiceException;

import com.cartmate.cartmate.shared.payment.dto.PaymentResult;

import com.cartmate.cartmate.shared.payment.dto.PaymentOrderType;

import com.cartmate.cartmate.boundedContext.orderService.order.out.OrderPaymentClient;

import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.Order;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.ItemCancelOutcome;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.OrderCancelPreview;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.itemcancel.OrderItemCancel;
import com.cartmate.cartmate.boundedContext.orderService.order.out.itemcancel.OrderItemCancelRepository;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.buyercancel.BuyerCancelRequest;
import com.cartmate.cartmate.shared.order.OrderStatus;
import com.cartmate.cartmate.boundedContext.orderService.order.out.personal.OrderRepository;
import com.cartmate.cartmate.shared.order.event.OrderCanceledEvent;
import com.cartmate.cartmate.shared.order.event.OrderCouponRestoreRequestedEvent;
import com.cartmate.cartmate.shared.order.event.OrderSellerItemsCanceledEvent;
import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
class OrderCancelUseCase {

    private final OrderRepository orderRepository;
    private final OrderSupport orderSupport;
    private final DomainEventPublisher eventPublisher;
    private final OrderPaymentClient orderPaymentClient;
    private final OrderItemCancelRepository orderItemCancelRepository;
    private final Clock clock;

    void cancelByBuyer(Long memberId, Long orderId) {
        Order order = orderSupport.getOrderForUpdate(orderId);
        order.cancelByBuyer(memberId, LocalDateTime.now(clock));
        publishCanceledEventIf(true, order, false);
    }

    BuyerCancelStart startBuyerCancel(Long memberId, Long orderId, Collection<Long> orderItemIds) {
        Order order = orderSupport.getOrderForUpdate(orderId);
        if (order.isPaymentPending()) {
            cancelByBuyer(memberId, orderId);
            return BuyerCancelStart.canceled();
        }
        order.previewBuyerCancel(memberId, orderItemIds);
        return BuyerCancelStart.request(order.buyerCancelTargets(memberId, orderItemIds).stream()
                .map(item -> new BuyerCancelRequest.Target(item.getId(), item.getSellerId()))
                .toList());
    }

    OrderCancelPreview previewBuyerCancel(Long memberId, Long orderId, Collection<Long> orderItemIds) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ServiceException(OrderErrorCode.ORDER_NOT_FOUND));
        return order.previewBuyerCancel(memberId, orderItemIds);
    }

    List<Long> findPaidIdsByProductIds(Collection<Long> productIds) {
        return orderRepository.findIdsByStatusInAndProductIds(List.of(OrderStatus.PAID), productIds);
    }

    void cancelByDeadline(Long orderId) {
        Order order = orderSupport.getOrderForUpdate(orderId);
        boolean paid = isPaid(order);
        publishCanceledEventIf(order.cancelByDeadline(LocalDateTime.now(clock)), order, paid);
    }

    void cancelByOutOfStock(Long orderId) {
        Order order = orderSupport.getOrderForUpdate(orderId);
        boolean paid = isPaid(order);
        publishCanceledEventIf(order.cancelByOutOfStock(LocalDateTime.now(clock)), order, paid);
    }

    void cancelBySalesStopped(Long orderId) {
        Order order = orderSupport.getOrderForUpdate(orderId);
        publishCanceledEventIf(order.cancelBySalesStopped(LocalDateTime.now(clock)), order, false);
    }

    void cancelByGroupPurchaseClosed(Long orderId) {
        Order order = orderSupport.getOrderForUpdate(orderId);
        publishCanceledEventIf(order.cancelByGroupPurchaseClosed(LocalDateTime.now(clock)), order, false);
    }

    List<Long> findPendingIdsOfIndividualGroupPurchase(Long individualGroupPurchaseId) {
        return orderRepository.findIdsByIndividualGroupPurchaseIdAndStatus(individualGroupPurchaseId,
                OrderStatus.PAYMENT_PENDING);
    }

    // 환불이 있거나 주문을 끝낸 취소만 취소 건으로 남긴다. 돌려줄 돈이 없는 일부 취소는 환불 이벤트도 내지 않는다.
    Optional<Long> cancelItems(Long orderId, Long sellerId, Collection<Long> orderItemIds,
                               DeliveryCancelReason reason) {
        Order order = orderSupport.getOrderForUpdate(orderId);
        LocalDateTime now = LocalDateTime.now(clock);
        ItemCancelOutcome outcome = order.cancelItems(sellerId, orderItemIds, reason, now);
        return recordCancels(order, outcome, reason, now).stream().findFirst();
    }

    List<Long> findUncanceledItemIds(Long orderId, Long sellerId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ServiceException(OrderErrorCode.ORDER_NOT_FOUND))
                .uncanceledItemIdsOf(sellerId);
    }

    // 구매자 취소 결과 하나로 취소된 판매자들의 상품을 한 번에 취소한다
    List<Long> cancelItemsByBuyer(Long orderId, Map<Long, List<Long>> itemIdsBySeller) {
        Order order = orderSupport.getOrderForUpdate(orderId);
        LocalDateTime now = LocalDateTime.now(clock);
        ItemCancelOutcome outcome = order.cancelItems(itemIdsBySeller, DeliveryCancelReason.BUYER_CANCELED, now);
        return recordCancels(order, outcome, DeliveryCancelReason.BUYER_CANCELED, now);
    }

    // 판매자 몫마다 환불이 있거나 주문을 끝낸 취소면 취소 건으로 남기고, 환불이 있으면 환불 이벤트를 낸다
    private List<Long> recordCancels(Order order, ItemCancelOutcome outcome, DeliveryCancelReason reason, LocalDateTime now) {
        List<Long> cancelIds = new ArrayList<>();
        for (ItemCancelOutcome.SellerPart part : outcome.parts()) {
            if (part.refundAmount() == 0 && !outcome.orderCanceled()) {
                continue;
            }
            Long cancelId = orderItemCancelRepository.save(OrderItemCancel.of(PurchaseType.INDIVIDUAL_GROUP,
                    order.getId(), part.sellerId(), reason, part.refundAmount(), now)).getId();
            part.linkCancel(cancelId);
            cancelIds.add(cancelId);
            if (part.refundAmount() > 0) {
                eventPublisher.publish(new OrderSellerItemsCanceledEvent(order.getId(), order.getBuyerMemberId(),
                        part.sellerId(), part.refundAmount(), now, reason, cancelId));
            }
        }
        if (outcome.couponReturnable()) {
            requestCouponRestore(order);
        }
        return cancelIds;
    }

    List<Long> findIdsByStatusAndProductIds(OrderStatus status, Collection<Long> productIds) {
        return orderRepository.findIdsByStatusAndProductIds(status, productIds);
    }

    List<Long> findIdsToCancelByDeadline(LocalDateTime now) {
        return orderRepository.findIdsToCancelByDeadline(now);
    }

    // 쿠폰 복구는 주문과 함께 커밋되는 이벤트로 요청한다(상품 서버가 받아 처리, 실패하면 다시 받는다)
    private void requestCouponRestore(Order order) {
        eventPublisher.publish(new OrderCouponRestoreRequestedEvent(order.getId(), order.getBuyerMemberId(),
                order.getMemberCouponId(), OrderCouponClient.ORDER_TYPE));
    }

    private boolean isPaid(Order order) {
        return order.isPaid();
    }

    // 결제 대기 주문이 어떤 사유로 취소되든, 결제 진행 때 쓴 쿠폰이 있으면 돌려준다(결제가 끝나지 못한 시도의 쿠폰 포함)
    private void publishCanceledEventIf(boolean canceled, Order order, boolean paid) {
        if (!canceled) {
            return;
        }
        if (!paid) {
            releaseUnappliedCharge(order);
        }
        eventPublisher.publish(toCanceledEvent(order, paid));
        if (order.couponRestorable()) {
            requestCouponRestore(order);
            order.markCouponReturned();
        }
    }

    private void releaseUnappliedCharge(Order order) {
        Long orderId = order.getId();
        Long buyer = order.getBuyerMemberId();
        if (orderPaymentClient.findPayment(PaymentOrderType.INDIVIDUAL_GROUP, orderId, buyer)
                .filter(PaymentResult::isPaid).isEmpty()) {
            return;
        }
        log.warn("결제 대기 주문 취소 중 반영 안 된 결제 발견, 되돌림: orderId={} buyer={}", orderId, buyer);
        orderPaymentClient.compensateCharge(PaymentOrderType.INDIVIDUAL_GROUP, orderId, buyer);
        if (orderPaymentClient.findPayment(PaymentOrderType.INDIVIDUAL_GROUP, orderId, buyer)
                .filter(PaymentResult::isPaid).isPresent()) {
            throw new ServiceException(OrderErrorCode.ORDER_PAYMENT_RELEASE_PENDING);
        }
    }

    private OrderCanceledEvent toCanceledEvent(Order order, boolean paid) {
        return new OrderCanceledEvent(
                order.getId(),
                order.getBuyerMemberId(),
                order.getOrderType(),
                order.getCancelReason(),
                paid,
                order.getCanceledAt(),
                paid ? order.remainingRefundAmount() : null
        );
    }
}
