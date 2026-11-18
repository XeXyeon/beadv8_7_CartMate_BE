package com.cartmate.cartmate.boundedContext.orderService.order.app.personal;

import com.cartmate.cartmate.shared.coupon.dto.CouponQuote;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.OrderCouponRule;
import com.cartmate.cartmate.shared.coupon.dto.CouponOption;
import com.cartmate.cartmate.boundedContext.orderService.order.out.OrderCouponClient;
import com.cartmate.cartmate.boundedContext.orderService.order.out.OrderInventoryClient;
import com.cartmate.cartmate.shared.product.dto.InventoryReserveRequest;
import com.cartmate.cartmate.global.http.InternalApiClient.InternalServerUnavailableException;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.DeliveryAddress;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.Order;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.OrderItem;
import com.cartmate.cartmate.boundedContext.orderService.order.dto.personal.OrderPaymentPreviewResponse;
import com.cartmate.cartmate.boundedContext.orderService.order.exception.OrderErrorCode;
import com.cartmate.cartmate.boundedContext.orderService.order.out.OrderPaymentClient;
import com.cartmate.cartmate.boundedContext.orderService.order.out.OrderProductClient;
import com.cartmate.cartmate.boundedContext.orderService.order.out.OrderProductInfo;
import com.cartmate.cartmate.boundedContext.orderService.order.out.personal.OrderRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.shared.order.dto.DeliveryAddressInfo;
import com.cartmate.cartmate.shared.order.dto.OrderPaymentInfo;
import com.cartmate.cartmate.shared.order.event.OrderPaidEvent;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.shared.payment.dto.PaymentOrderType;
import com.cartmate.cartmate.shared.payment.dto.PaymentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Predicate;

@Slf4j
@Service
@RequiredArgsConstructor
class OrderPaymentUseCase {

    private final OrderRepository orderRepository;
    private final OrderSupport orderSupport;
    private final DomainEventPublisher eventPublisher;
    private final OrderProductClient orderProductClient;
    private final IndividualGroupPurchaseReplicaUseCase individualGroupPurchaseReplicaUseCase;
    private final OrderPaymentClient orderPaymentClient;
    private final OrderCouponClient orderCouponClient;
    private final OrderInventoryClient orderInventoryClient;
    // pay()의 짧은 트랜잭션들(잠금+선점 / 반영 / 표시 해제). pay()는 트랜잭션 밖에서 불려 각각 새 트랜잭션이 된다
    private final TransactionOperations transaction;

    OrderPaymentInfo getPaymentInfo(Long orderId, Long memberId, LocalDateTime now) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ServiceException(OrderErrorCode.ORDER_NOT_FOUND));
        orderSupport.validateBuyer(order, memberId);
        return new OrderPaymentInfo(
                order.getId(),
                memberId,
                order.getOrderNumber(),
                order.getTotalAmount(),
                order.canAcceptPayment(now) && groupPurchaseAcceptsPayment(order, now, false),
                order.getPaymentDeadlineAt()
        );
    }

    void applyPayment(Long orderId, Long memberId, Long paymentId, LocalDateTime paidAt) {
        applyPayment(orderId, memberId, paymentId, paidAt, null, null);
    }

    void applyPayment(Long orderId, Long memberId, Long paymentId, LocalDateTime paidAt,
                      Long memberCouponId, Long paidAmount) {
        Order order = orderSupport.getOrderForUpdate(orderId);
        orderSupport.validateBuyer(order, memberId);
        if (order.isPaymentPending()) {
            // 결제 직후라 이 주문의 재고 선점이 이미 잡혀 있다. 남은 수량은 보지 않고 판매 중인지만 다시 본다
            validateOnSale(order.getItems().stream().map(OrderItem::getInventoryId).toList());
            if (!groupPurchaseAcceptsPayment(order, paidAt, true)) {
                throw new ServiceException(OrderErrorCode.ORDER_GROUP_PURCHASE_PAYMENT_CLOSED);
            }
        }
        if (memberCouponId != null && order.isPaymentPending()) {
            CouponQuote quote = orderCouponClient.use(memberId, memberCouponId, orderId, order.couponTarget());
            order.applyCoupon(quote.memberCouponId(), quote.discountAmount(), quote.platformBurden(), quote.sellerId(),
                    OrderCouponRule.of(quote.discountType(), quote.discountValue(),
                            quote.maxDiscountAmount(), quote.minOrderAmount()));
        }
        if (paidAmount != null && order.isPaymentPending() && order.payAmount() != paidAmount) {
            throw new ServiceException(OrderErrorCode.ORDER_PAYMENT_AMOUNT_MISMATCH);
        }
        if (order.markPaid(paymentId, paidAt)) {
            eventPublisher.publish(toPaidEvent(order));
        }
    }

    private OrderPaidEvent toPaidEvent(Order order) {
        DeliveryAddress address = order.getDeliveryAddress();
        return new OrderPaidEvent(
                order.getId(),
                order.getOrderNumber(),
                order.getBuyerMemberId(),
                order.getOrderType(),
                order.getIndividualGroupPurchaseId(),
                order.getPaidAt(),
                new DeliveryAddressInfo(
                        address.getReceiverName(),
                        address.getReceiverPhone(),
                        address.getZipCode(),
                        address.getAddress(),
                        address.getAddressDetail()
                ),
                order.getItems().stream()
                        .map(item -> new OrderPaidEvent.Item(
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

    private boolean groupPurchaseAcceptsPayment(Order order, LocalDateTime now, boolean forPayment) {
        if (!order.isIndividualGroup()) {
            return true;
        }
        Long purchaseId = order.getIndividualGroupPurchaseId();
        return (forPayment ? individualGroupPurchaseReplicaUseCase.findForPayment(purchaseId)
                : individualGroupPurchaseReplicaUseCase.find(purchaseId))
                .map(purchase -> purchase.canAcceptPayment(order.productIds(), now))
                .orElse(false);
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

    PaymentResult pay(Long orderId, Long memberId, LocalDateTime now) {
        return pay(orderId, memberId, null, now);
    }

    /**
     * 개인 주문 결제. 트랜잭션 밖에서 부른다 — 다른 서버 호출(상품·쿠폰·재고·결제) 동안 DB 커넥션을 잡지 않는다.
     *
     * <p>같은 주문의 결제는 한 번에 하나만 진행한다: 주문 행을 잠그고 결제 진행 표시를 건 요청만 선점·차감·반영하고,
     * 표시가 살아 있는 동안 들어온 요청은 409(ORDER_PAYMENT_IN_PROGRESS)로 바로 거절한다.
     * 그래서 실패한 요청이 진행 중인 다른 요청의 재고 선점을 푸는 일이 없다(선점은 주문 단위라 누가 잡았는지 구분하지 않는다).
     */
    PaymentResult pay(Long orderId, Long memberId, Long memberCouponId, LocalDateTime now) {
        var existing = orderPaymentClient.findPayment(PaymentOrderType.INDIVIDUAL_GROUP, orderId, memberId)
                .filter(PaymentResult::isPaid);
        if (existing.isPresent()) {
            // 이미 차감됨(응답을 못 받았던 재시도 등): 반영만 한다
            LocalDateTime lockedUntil = transaction.execute(status -> lockPayment(orderId, memberId, now, false));
            return applyOrCompensate(orderId, memberId, existing.get(), memberCouponId, lockedUntil);
        }
        // 잠그기 전에 확인 · 할인 계산 (주문 상품은 결제 대기 중 바뀌지 않는다)
        Order order = orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new ServiceException(OrderErrorCode.ORDER_NOT_FOUND));
        orderSupport.validateBuyer(order, memberId);
        if (!order.canAcceptPayment(now) || !groupPurchaseAcceptsPayment(order, now, false)) {
            throw new ServiceException(OrderErrorCode.ORDER_NOT_PAYABLE);
        }
        validatePurchasable(order.getItems().stream().map(OrderItem::getInventoryId).toList());
        long discount = memberCouponId == null ? 0
                : orderCouponClient.quote(memberId, memberCouponId, orderId, order.couponTarget()).discountAmount();
        // 결제 진행 표시 + 재고 선점을 한 트랜잭션으로 커밋. 부족하면 ORDER_OUT_OF_STOCK(표시 없이 롤백, 주문 취소는 호출한 쪽이 따로 한다)
        LocalDateTime lockedUntil = transaction.execute(status -> lockPayment(orderId, memberId, now, true));
        PaymentResult payment;
        try {
            payment = orderPaymentClient.charge(
                    PaymentOrderType.INDIVIDUAL_GROUP, orderId, memberId, order.getTotalAmount() - discount);
        } catch (InternalServerUnavailableException e) {
            // 결제됐는지 모른다 → 선점도 표시도 그대로(표시는 만료, 선점은 재시도 · 결제 기한 취소가 정리)
            throw e;
        } catch (ServiceException e) {
            // 같은 키의 차감이 아직 진행 중이면 결과를 모르는 것과 같다 → 그대로 둔다
            if (!OrderErrorCode.ORDER_PAYMENT_IN_PROGRESS.resultCode().equals(e.getRsData().getResultCode())) {
                releaseStock(orderId);   // 결제 실패가 확실 → 선점 해제 (표시를 쥔 요청만 여기 온다)
                unlockPayment(orderId, lockedUntil);
            }
            throw e;
        }
        return applyOrCompensate(orderId, memberId, payment, memberCouponId, lockedUntil);
    }

    // 주문 행을 잠그고 결제 진행 표시를 건다. reserve면 결제 가능 여부를 다시 보고 재고도 선점한다(행을 잠근 채라 같은 주문 선점이 겹치지 않는다)
    private LocalDateTime lockPayment(Long orderId, Long memberId, LocalDateTime now, boolean reserve) {
        Order order = orderSupport.getOrderForUpdate(orderId);
        orderSupport.validateBuyer(order, memberId);
        if (reserve && !order.canAcceptPayment(now)) {
            throw new ServiceException(OrderErrorCode.ORDER_NOT_PAYABLE);
        }
        LocalDateTime lockedUntil = order.lockPayment(now);
        if (reserve) {
            reserveStock(orderId, order);
        }
        return lockedUntil;
    }

    // 표시를 푸는 실패가 원래 오류를 가리지 않게 로그만 남긴다(표시는 만료되면 저절로 풀린다)
    private void unlockPayment(Long orderId, LocalDateTime lockedUntil) {
        try {
            transaction.executeWithoutResult(status -> orderRepository.unlockPayment(orderId, lockedUntil));
        } catch (RuntimeException e) {
            log.warn("결제 진행 표시 해제 실패 — {} 뒤 저절로 풀린다: 주문 #{}", Order.PAYMENT_LOCK_PERIOD, orderId, e);
        }
    }

    private void reserveStock(Long orderId, Order order) {
        orderInventoryClient.reserve(order.getOrderType(), orderId, order.getItems().stream()
                .map(item -> new InventoryReserveRequest.Item(item.getInventoryId(), item.getQuantity()))
                .toList());
    }

    // 선점 해제 실패가 원래 오류를 가리지 않게 로그만 남긴다(결제 기한 취소 이벤트로 다시 풀린다)
    private void releaseStock(Long orderId) {
        try {
            orderRepository.findById(orderId)
                    .ifPresent(order -> orderInventoryClient.release(order.getOrderType(), orderId));
        } catch (RuntimeException e) {
            log.warn("결제 실패 후 재고 선점 해제 실패 — 결제 기한 취소 때 다시 풀린다: 주문 #{}", orderId, e);
        }
    }

    // 결제 완료로 바꾸면 진행 표시도 같이 지워진다(markPaid). 실패하면 정리한 뒤 표시를 푼다:
    // 차감은 이미 확정이라 다음 요청은 같은 결제를 받아 반영만 하므로 기다리게 할 이유가 없다
    private PaymentResult applyOrCompensate(Long orderId, Long memberId, PaymentResult payment, Long memberCouponId,
                                            LocalDateTime lockedUntil) {
        try {
            transaction.executeWithoutResult(status -> applyPayment(
                    orderId, memberId, payment.paymentId(), payment.paidAt(), memberCouponId, payment.amount()));
            return payment;
        } catch (InternalServerUnavailableException e) {
            releaseCoupon(orderId, memberId, memberCouponId);
            unlockPayment(orderId, lockedUntil);
            throw e;
        } catch (ServiceException e) {
            orderPaymentClient.compensateCharge(PaymentOrderType.INDIVIDUAL_GROUP, orderId, memberId);
            releaseStock(orderId);
            releaseCoupon(orderId, memberId, memberCouponId);
            unlockPayment(orderId, lockedUntil);
            throw e;
        }
    }

    private void releaseCoupon(Long orderId, Long memberId, Long memberCouponId) {
        if (memberCouponId == null) {
            return;
        }
        try {
            if (orderRepository.findById(orderId).filter(Order::isPaymentPending).isEmpty()) {
                return;
            }
            orderCouponClient.restore(memberId, memberCouponId, orderId);
        } catch (RuntimeException e) {
            log.error("결제 반영 실패 후 쿠폰 복구 실패 — 확인 필요: 주문 #{} 회원 #{} 쿠폰 #{}", orderId, memberId, memberCouponId, e);
        }
    }

    List<CouponOption> couponOptions(Long orderId, Long memberId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ServiceException(OrderErrorCode.ORDER_NOT_FOUND));
        orderSupport.validateBuyer(order, memberId);
        return orderCouponClient.options(memberId, orderId, order.couponTarget());
    }

    OrderPaymentPreviewResponse preview(Long orderId, Long memberId, LocalDateTime now) {
        return preview(orderId, memberId, null, now);
    }

    OrderPaymentPreviewResponse preview(Long orderId, Long memberId, Long memberCouponId, LocalDateTime now) {
        OrderPaymentInfo info = getPaymentInfo(orderId, memberId, now);
        PaymentResult existing = orderPaymentClient
                .findPayment(PaymentOrderType.INDIVIDUAL_GROUP, orderId, memberId).orElse(null);
        long discount = 0;
        if (memberCouponId != null) {
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new ServiceException(OrderErrorCode.ORDER_NOT_FOUND));
            discount = orderCouponClient.quote(memberId, memberCouponId, orderId, order.couponTarget()).discountAmount();
        }
        long amount = info.amount() - discount;
        long balance = orderPaymentClient.getWalletBalance(memberId);
        return new OrderPaymentPreviewResponse(orderId, amount, info.payable(), balance,
                balance - amount, balance >= amount, existing, info.amount(), discount, memberCouponId);
    }
}
