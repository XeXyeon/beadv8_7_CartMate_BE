package com.cartmate.cartmate.boundedContext.orderService.order.app.groupshared;

import com.cartmate.cartmate.global.http.InternalApiClient.InternalServerUnavailableException;

import com.cartmate.cartmate.boundedContext.orderService.order.domain.DeliveryAddress;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.groupshared.SharedGroupOrder;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.groupshared.SharedGroupOrderItem;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.groupshared.SharedGroupOrderParticipant;
import com.cartmate.cartmate.shared.order.SharedGroupOrderStatus;
import com.cartmate.cartmate.boundedContext.orderService.order.exception.OrderErrorCode;
import com.cartmate.cartmate.boundedContext.orderService.order.out.OrderCouponClient;
import com.cartmate.cartmate.boundedContext.orderService.order.out.OrderPaymentClient;
import com.cartmate.cartmate.shared.coupon.dto.CouponOption;
import com.cartmate.cartmate.shared.coupon.dto.CouponOrderTarget;
import com.cartmate.cartmate.shared.coupon.dto.CouponQuote;
import com.cartmate.cartmate.shared.order.event.OrderCouponRestoreRequestedEvent;
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
    private final OrderCouponClient orderCouponClient;

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
        applyPayment(sharedGroupOrderId, memberId, paymentId, paidAt, teamDeliveryAddress, null, null);
    }

    // usedCoupon은 상품 서버에서 이 참여자의 분담금에 사용 처리된 쿠폰(없으면 null), paidAmount는 실제 차감액(없으면 확인 안 함)
    private void applyPayment(Long sharedGroupOrderId, Long memberId, Long paymentId, LocalDateTime paidAt,
                              TeamDeliveryAddress teamDeliveryAddress, CouponQuote usedCoupon, Long paidAmount) {
        SharedGroupOrder order = sharedGroupOrderSupport.getOrderForUpdate(sharedGroupOrderId);
        if (order.getStatus() == SharedGroupOrderStatus.PAYING) {
            // 결제 직후라 이 주문의 재고 선점이 이미 잡혀 있다. 남은 수량은 보지 않고 판매 중인지만 다시 본다
            validateOnSale(order.getItems().stream().map(SharedGroupOrderItem::getInventoryId).toList());
        }
        DeliveryAddress deliveryAddress = order.isCreator(memberId) ? toDeliveryAddress(teamDeliveryAddress) : null;
        Long memberCouponId = usedCoupon == null ? null : usedCoupon.memberCouponId();
        long discount = usedCoupon == null ? 0 : usedCoupon.discountAmount();
        if (order.pay(memberId, paymentId, paidAt, deliveryAddress, memberCouponId, discount, paidAmount)) {
            eventPublisher.publish(toConfirmedEvent(order));
        }
    }

    // ===== 분담금 쿠폰 (플랫폼 쿠폰만: 대상 금액에 판매자별 금액을 싣지 않아 판매자 쿠폰은 "적용 대상 아님"이 된다) =====

    List<CouponOption> couponOptions(Long sharedGroupOrderId, Long memberId) {
        SharedGroupOrder order = sharedGroupOrderRepository.findById(sharedGroupOrderId)
                .orElseThrow(() -> new ServiceException(OrderErrorCode.ORDER_NOT_FOUND));
        return orderCouponClient.options(OrderCouponClient.SHARED_GROUP_ORDER_TYPE, memberId, sharedGroupOrderId,
                couponTarget(order, memberId));
    }

    // 결제 진행 때 이 참여자가 쓸 쿠폰을 기록한다(호출한 쪽 트랜잭션). 앞 시도가 다른 쿠폰을 기록해 뒀으면 그 쿠폰 복구를 요청한다
    void recordCoupon(Long sharedGroupOrderId, Long memberId, Long memberCouponId) {
        SharedGroupOrder order = sharedGroupOrderSupport.getOrderForUpdate(sharedGroupOrderId);
        Long replaced = order.recordParticipantCoupon(memberId, memberCouponId);
        if (replaced != null) {
            requestCouponRestore(sharedGroupOrderId, memberId, replaced);
        }
    }

    // 결제가 실패로 끝났을 때 이 참여자가 기록한 쿠폰의 복구를 요청한다(호출한 쪽 트랜잭션). 이미 결제했으면 아무것도 하지 않는다
    void releaseCoupon(Long sharedGroupOrderId, Long memberId) {
        SharedGroupOrder order = sharedGroupOrderSupport.getOrderForUpdate(sharedGroupOrderId);
        Long released = order.releaseParticipantCoupon(memberId);
        if (released != null) {
            requestCouponRestore(sharedGroupOrderId, memberId, released);
        }
    }

    // 결제 진행 중 기록해 둔 쿠폰(결과를 모르는 앞 시도가 남긴 것). 없으면 null
    Long recordedCoupon(Long sharedGroupOrderId, Long memberId) {
        return sharedGroupOrderRepository.findById(sharedGroupOrderId)
                .map(order -> order.getParticipant(memberId))
                .filter(participant -> !participant.isPaid())
                .map(SharedGroupOrderParticipant::getMemberCouponId)
                .orElse(null);
    }

    // 쿠폰 대상 금액(내 분담금). 참여자 목록이 지연 로딩이라 트랜잭션 안에서 부른다
    CouponOrderTarget couponTarget(Long sharedGroupOrderId, Long memberId) {
        SharedGroupOrder order = sharedGroupOrderRepository.findById(sharedGroupOrderId)
                .orElseThrow(() -> new ServiceException(OrderErrorCode.ORDER_NOT_FOUND));
        return couponTarget(order, memberId);
    }

    // 상품 서버에서 쿠폰을 이 참여자의 분담금에 사용 처리한다(트랜잭션 밖, DB를 읽지 않는다). 같은 주문에 다시 부르면 같은 결과다
    CouponQuote useCoupon(Long sharedGroupOrderId, Long memberId, Long memberCouponId, CouponOrderTarget target) {
        return orderCouponClient.use(OrderCouponClient.SHARED_GROUP_ORDER_TYPE, memberId, memberCouponId,
                sharedGroupOrderId, target);
    }

    // 쿠폰 대상 금액은 이 참여자의 분담금. 판매자별 금액은 비워 판매자 쿠폰은 쓸 수 없다
    private static CouponOrderTarget couponTarget(SharedGroupOrder order, Long memberId) {
        return new CouponOrderTarget(order.getParticipant(memberId).getShareAmount(), List.of());
    }

    private void requestCouponRestore(Long sharedGroupOrderId, Long memberId, Long memberCouponId) {
        eventPublisher.publish(new OrderCouponRestoreRequestedEvent(sharedGroupOrderId, memberId, memberCouponId,
                OrderCouponClient.SHARED_GROUP_ORDER_TYPE));
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
                                item.getUnitPrice(),
                                item.platformDiscountOrZero()))
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
        return pay(sharedGroupOrderId, memberId, teamDeliveryAddress, null, now);
    }

    // usedCoupon: 호출한 쪽이 이 트랜잭션 전에 사용 처리한 쿠폰(없으면 null). 그 할인만큼 덜 차감한다
    PaymentResult pay(Long sharedGroupOrderId, Long memberId, TeamDeliveryAddress teamDeliveryAddress,
                      CouponQuote usedCoupon, LocalDateTime now) {
        var existing = orderPaymentClient.findPayment(PaymentOrderType.SHARED_GROUP, sharedGroupOrderId, memberId);
        if (existing.isPresent() && existing.get().isPaid()) {
            return applyOrCompensate(sharedGroupOrderId, memberId, existing.get(), teamDeliveryAddress, usedCoupon);
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
        long discount = usedCoupon == null ? 0 : usedCoupon.discountAmount();
        PaymentResult payment = orderPaymentClient.charge(
                PaymentOrderType.SHARED_GROUP, sharedGroupOrderId, memberId, info.shareAmount() - discount);
        return applyOrCompensate(sharedGroupOrderId, memberId, payment, teamDeliveryAddress, usedCoupon);
    }

    private PaymentResult applyOrCompensate(Long sharedGroupOrderId, Long memberId, PaymentResult payment,
                                            TeamDeliveryAddress teamDeliveryAddress, CouponQuote usedCoupon) {
        try {
            applyPayment(sharedGroupOrderId, memberId, payment.paymentId(), payment.paidAt(), teamDeliveryAddress,
                    usedCoupon, payment.amount());
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
