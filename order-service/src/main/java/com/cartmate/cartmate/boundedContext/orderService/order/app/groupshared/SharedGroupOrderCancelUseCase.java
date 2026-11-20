package com.cartmate.cartmate.boundedContext.orderService.order.app.groupshared;

import com.cartmate.cartmate.boundedContext.orderService.order.domain.groupshared.SharedGroupOrder;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.groupshared.SharedItemCancelOutcome;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.itemcancel.OrderItemCancel;
import com.cartmate.cartmate.boundedContext.orderService.order.out.itemcancel.OrderItemCancelRepository;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import com.cartmate.cartmate.shared.order.SharedGroupOrderStatus;
import com.cartmate.cartmate.boundedContext.orderService.order.out.groupshared.SharedGroupOrderRepository;
import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import com.cartmate.cartmate.shared.order.event.SharedGroupOrderCanceledEvent.SellerAmount;
import com.cartmate.cartmate.shared.order.event.SharedGroupOrderSellerItemsCanceledEvent;
import com.cartmate.cartmate.shared.order.event.OrderCouponRestoreRequestedEvent;
import com.cartmate.cartmate.boundedContext.orderService.order.out.OrderCouponClient;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.boundedContext.orderService.order.exception.OrderErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
class SharedGroupOrderCancelUseCase {

    private final SharedGroupOrderRepository sharedGroupOrderRepository;
    private final SharedGroupOrderSupport sharedGroupOrderSupport;
    private final DomainEventPublisher eventPublisher;
    private final OrderItemCancelRepository orderItemCancelRepository;
    private final Clock clock;

    void cancelByCreator(Long memberId, Long sharedGroupOrderId) {
        SharedGroupOrder order = sharedGroupOrderSupport.getOrderForUpdate(sharedGroupOrderId);
        publishCanceledEventIf(order.cancelByCreator(memberId, LocalDateTime.now(clock)), order);
    }

    void cancelByDeadline(Long sharedGroupOrderId) {
        SharedGroupOrder order = sharedGroupOrderSupport.getOrderForUpdate(sharedGroupOrderId);
        publishCanceledEventIf(order.cancelByDeadline(LocalDateTime.now(clock)), order);
    }

    void cancelByOutOfStock(Long sharedGroupOrderId) {
        SharedGroupOrder order = sharedGroupOrderSupport.getOrderForUpdate(sharedGroupOrderId);
        publishCanceledEventIf(order.cancelByOutOfStock(LocalDateTime.now(clock)), order);
    }

    void cancelBySalesStopped(Long sharedGroupOrderId) {
        SharedGroupOrder order = sharedGroupOrderSupport.getOrderForUpdate(sharedGroupOrderId);
        publishCanceledEventIf(order.cancelBySalesStopped(LocalDateTime.now(clock)), order);
    }

    Optional<Long> cancelItems(Long sharedGroupOrderId, Long sellerId, Collection<Long> orderItemIds,
                               DeliveryCancelReason reason) {
        SharedGroupOrder order = sharedGroupOrderSupport.getOrderForUpdate(sharedGroupOrderId);
        LocalDateTime now = LocalDateTime.now(clock);
        List<SellerAmount> batchesBefore = order.canceledBatches();
        SharedItemCancelOutcome outcome = order.cancelItems(sellerId, orderItemIds, reason, now);
        if (outcome.isEmpty()) {
            return Optional.empty();
        }
        Long cancelId = orderItemCancelRepository.save(OrderItemCancel.of(PurchaseType.SHARED_GROUP,
                sharedGroupOrderId, sellerId, reason, outcome.amount(), now)).getId();
        outcome.linkCancel(cancelId);
        // 마지막 판매자 몫까지 취소되면 전체 취소로 환불한다.
        // 판매자 몫마다 따로 나누면 1원 끝전이 한 사람에게 쌓여, 낸 돈보다 많이 돌려주려다 실패할 수 있다.
        if (outcome.orderCanceled()) {
            eventPublisher.publish(order.toCanceledEvent(batchesBefore));
            requestCouponRestores(order);
        } else if (outcome.amount() > 0) {
            eventPublisher.publish(new SharedGroupOrderSellerItemsCanceledEvent(
                    sharedGroupOrderId, sellerId, outcome.amount(), now, reason, cancelId));
        }
        return Optional.of(cancelId);
    }

    List<Long> findUncanceledItemIds(Long sharedGroupOrderId, Long sellerId) {
        return sharedGroupOrderRepository.findById(sharedGroupOrderId)
                .orElseThrow(() -> new ServiceException(OrderErrorCode.ORDER_NOT_FOUND))
                .uncanceledItemIdsOf(sellerId);
    }

    List<Long> findIdsByStatusAndProductIds(SharedGroupOrderStatus status, Collection<Long> productIds) {
        return sharedGroupOrderRepository.findIdsByStatusInAndProductIds(List.of(status), productIds);
    }

    List<Long> findIdsToCancelByDeadline(LocalDateTime now) {
        return sharedGroupOrderRepository.findIdsToCancelByDeadline(now);
    }

    private void publishCanceledEventIf(boolean canceled, SharedGroupOrder order) {
        if (canceled) {
            eventPublisher.publish(order.toCanceledEvent(order.canceledBatches()));
            requestCouponRestores(order);
        }
    }

    // 공동 주문이 취소되면 참여자들이 분담금에 쓴 쿠폰을 돌려준다(결제 도중 쓴 쿠폰 포함). 주문과 함께 커밋되는 이벤트로 요청한다
    private void requestCouponRestores(SharedGroupOrder order) {
        order.takeRestorableCoupons().forEach(coupon -> eventPublisher.publish(new OrderCouponRestoreRequestedEvent(
                order.getId(), coupon.memberId(), coupon.memberCouponId(), OrderCouponClient.SHARED_GROUP_ORDER_TYPE)));
    }
}
