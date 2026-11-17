package com.cartmate.cartmate.boundedContext.orderService.order.app.groupshared;

import com.cartmate.cartmate.global.http.InternalApiClient.InternalServerUnavailableException;

import com.cartmate.cartmate.boundedContext.orderService.order.domain.DeliveryAddress;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.groupshared.SharedGroupOrder;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.groupshared.SharedGroupOrderItem;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.groupshared.SharedGroupOrderParticipant;
import com.cartmate.cartmate.shared.order.SharedGroupOrderStatus;
import com.cartmate.cartmate.boundedContext.orderService.order.exception.OrderErrorCode;
import com.cartmate.cartmate.boundedContext.orderService.order.out.OrderPaymentClient;
import com.cartmate.cartmate.boundedContext.orderService.order.out.OrderProductClient;
import com.cartmate.cartmate.boundedContext.orderService.order.out.OrderProductInfo;
import com.cartmate.cartmate.boundedContext.orderService.order.out.groupshared.SharedGroupOrderRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.shared.order.dto.SharedGroupOrderPaymentInfo;
import com.cartmate.cartmate.shared.order.dto.TeamDeliveryAddress;
import com.cartmate.cartmate.shared.order.event.SharedGroupOrderConfirmedEvent;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.shared.payment.dto.PaymentOrderType;
import com.cartmate.cartmate.shared.payment.dto.PaymentResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Predicate;

@Service
@RequiredArgsConstructor
class SharedGroupOrderPaymentUseCase {

    private final SharedGroupOrderRepository sharedGroupOrderRepository;
    private final SharedGroupOrderSupport sharedGroupOrderSupport;
    private final DomainEventPublisher eventPublisher;
    private final OrderProductClient orderProductClient;
    private final OrderPaymentClient orderPaymentClient;
    private final SharedGroupOrderStockReserver sharedGroupOrderStockReserver;

    SharedGroupOrderPaymentInfo getPaymentInfo(Long sharedGroupOrderId, Long memberId, LocalDateTime now) {
        SharedGroupOrder order = sharedGroupOrderRepository.findById(sharedGroupOrderId)
                .orElseThrow(() -> new ServiceException(OrderErrorCode.ORDER_NOT_FOUND));
        SharedGroupOrderParticipant participant = order.getParticipant(memberId);
        return new SharedGroupOrderPaymentInfo(
                order.getId(),
                memberId,
                participant.getShareAmount(),
                order.canAcceptPayment(now) && !participant.isPaid(),
                order.needsStockReservation(),
                order.isCreator(memberId),
                order.getPaymentDeadlineAt()
        );
    }

    void markStockReserved(Long sharedGroupOrderId) {
        SharedGroupOrder order = sharedGroupOrderSupport.getOrderForUpdate(sharedGroupOrderId);
        if (order.getStatus() != SharedGroupOrderStatus.PAYING) {
            throw new ServiceException(OrderErrorCode.ORDER_NOT_PAYING);
        }
        if (order.isStockReserved()) {
            return;
        }
        order.markStockReserved();
    }

    void applyPayment(Long sharedGroupOrderId, Long memberId, Long paymentId, LocalDateTime paidAt,
                      TeamDeliveryAddress teamDeliveryAddress) {
        SharedGroupOrder order = sharedGroupOrderSupport.getOrderForUpdate(sharedGroupOrderId);
        if (order.getStatus() == SharedGroupOrderStatus.PAYING) {
            // 결제 직후라 이 주문의 재고 선점이 이미 잡혀 있다. 남은 수량은 보지 않고 판매 중인지만 다시 본다
            validateOnSale(order.getItems().stream().map(SharedGroupOrderItem::getInventoryId).toList());
        }
        DeliveryAddress deliveryAddress = order.isCreator(memberId) ? toDeliveryAddress(teamDeliveryAddress) : null;
        if (order.pay(memberId, paymentId, paidAt, deliveryAddress)) {
            eventPublisher.publish(toConfirmedEvent(order));
        }
    }

    private DeliveryAddress toDeliveryAddress(TeamDeliveryAddress teamDeliveryAddress) {
        if (teamDeliveryAddress == null) {
            return null;
        }
        return DeliveryAddress.of(
                teamDeliveryAddress.receiverName(),
                teamDeliveryAddress.receiverPhone(),
                teamDeliveryAddress.zipCode(),
                teamDeliveryAddress.address(),
                teamDeliveryAddress.addressDetail()
        );
    }

    private SharedGroupOrderConfirmedEvent toConfirmedEvent(SharedGroupOrder order) {
        DeliveryAddress address = order.getTeamDeliveryAddress();
        return new SharedGroupOrderConfirmedEvent(
                order.getId(),
                order.getSharedGroupPurchaseId(),
                order.getCreatorMemberId(),
                order.getConfirmedAt(),
                new TeamDeliveryAddress(
                        address.getReceiverName(),
                        address.getReceiverPhone(),
                        address.getZipCode(),
                        address.getAddress(),
                        address.getAddressDetail()
                ),
                order.getItems().stream()
                        .map(item -> new SharedGroupOrderConfirmedEvent.Item(
                                item.getId(),
                                item.getProductId(),
                                item.getInventoryId(),
                                item.getSellerId(),
                                item.getProductName(),
                                item.getOptionName(),
                                item.getQuantity(),
                                item.getUnitPrice()))
                        .toList()
        );
    }

    // 결제 전: 판매 중 + 남은 수량 1개 이상
    private void validatePurchasable(List<Long> inventoryIds) {
        validateProducts(inventoryIds, OrderProductInfo::purchasable);
    }

    // 결제 직후: 판매 중인지만 (내 선점으로 남은 수량이 0이 될 수 있다)
    private void validateOnSale(List<Long> inventoryIds) {
        validateProducts(inventoryIds, OrderProductInfo::onSale);
    }

    private void validateProducts(List<Long> inventoryIds, Predicate<OrderProductInfo> check) {
        List<OrderProductInfo> infos = orderProductClient.findAllByInventoryIds(inventoryIds);
        boolean ok = infos.size() == inventoryIds.stream().distinct().count()
                && infos.stream().allMatch(check);
        if (!ok) {
            throw new ServiceException(OrderErrorCode.ORDER_PRODUCT_NOT_PURCHASABLE);
        }
    }

    PaymentResult pay(Long sharedGroupOrderId, Long memberId, TeamDeliveryAddress teamDeliveryAddress, LocalDateTime now) {
        var existing = orderPaymentClient.findPayment(PaymentOrderType.SHARED_GROUP, sharedGroupOrderId, memberId);
        if (existing.isPresent() && existing.get().isPaid()) {
            return applyOrCompensate(sharedGroupOrderId, memberId, existing.get(), teamDeliveryAddress);
        }
        SharedGroupOrderPaymentInfo info = getPaymentInfo(sharedGroupOrderId, memberId, now);
        if (!info.payable()) {
            throw new ServiceException(OrderErrorCode.ORDER_NOT_PAYABLE);
        }
        if (info.teamAddressRequired() && teamDeliveryAddress == null) {
            throw new ServiceException(OrderErrorCode.ORDER_TEAM_ADDRESS_REQUIRED);
        }
        SharedGroupOrder order = sharedGroupOrderRepository.findById(sharedGroupOrderId)
                .orElseThrow(() -> new ServiceException(OrderErrorCode.ORDER_NOT_FOUND));
        List<Long> inventoryIds = order.getItems().stream().map(SharedGroupOrderItem::getInventoryId).toList();
        if (info.needsStockReservation()) {
            validatePurchasable(inventoryIds);
        } else {
            // 앞선 참여자의 결제로 이 주문의 재고 선점이 이미 잡혀 있다. 남은 수량은 보지 않고 판매 중인지만 본다
            validateOnSale(inventoryIds);
        }
        if (info.needsStockReservation()) {
            // 첫 결제: 상품 서버 재고 선점 + "선점함" 표시를 별도 트랜잭션으로 먼저 커밋한다
            // (이 결제가 실패해도 선점은 유지). 재고가 부족하면 ORDER_OUT_OF_STOCK — 차감하지 않는다
            sharedGroupOrderStockReserver.reserve(sharedGroupOrderId);
            // 위에서 커밋된 표시를 이 트랜잭션이 들고 있는 주문에도 반영한다(아래 결제 반영이 표시를 확인한다)
            markStockReserved(sharedGroupOrderId);
        }
        PaymentResult payment = orderPaymentClient.charge(
                PaymentOrderType.SHARED_GROUP, sharedGroupOrderId, memberId, info.shareAmount());
        return applyOrCompensate(sharedGroupOrderId, memberId, payment, teamDeliveryAddress);
    }

    private PaymentResult applyOrCompensate(Long sharedGroupOrderId, Long memberId, PaymentResult payment, TeamDeliveryAddress teamDeliveryAddress) {
        try {
            applyPayment(sharedGroupOrderId, memberId, payment.paymentId(), payment.paidAt(), teamDeliveryAddress);
            return payment;
        } catch (InternalServerUnavailableException e) {
            throw e;
        } catch (ServiceException e) {
            if (!isMissingTeamAddress(e)) {
                orderPaymentClient.compensateCharge(PaymentOrderType.SHARED_GROUP, sharedGroupOrderId, memberId);
            }
            throw e;
        }
    }

    private static boolean isMissingTeamAddress(ServiceException e) {
        return OrderErrorCode.ORDER_TEAM_ADDRESS_REQUIRED.resultCode().equals(e.getResultCode());
    }
}
