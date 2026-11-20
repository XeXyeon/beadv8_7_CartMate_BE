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

import com.cartmate.cartmate.shared.order.event.OrderCouponRestoreRequestedEvent;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

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

    // 쿠폰 없이 결제를 반영한다(호출한 쪽 트랜잭션 안에서 판매 확인까지 한다)
    void applyPayment(Long orderId, Long memberId, Long paymentId, LocalDateTime paidAt) {
        applyPayment(orderId, memberId, paymentId, paidAt, null, null, true);
    }

    // 결제 반영 트랜잭션(pay()의 마지막 단계). DB만 다룬다 — 다른 서버 호출(판매 확인·쿠폰 사용)은 이 트랜잭션 전에 끝낸다.
    // usedCoupon은 상품 서버에서 이 주문에 사용 처리된 쿠폰(없으면 null). 할인을 주문과 상품 줄에 고정한다.
    private void applyPayment(Long orderId, Long memberId, Long paymentId, LocalDateTime paidAt,
                              CouponQuote usedCoupon, Long paidAmount, boolean checkOnSale) {
        Order order = orderSupport.getOrderForUpdate(orderId);
        orderSupport.validateBuyer(order, memberId);
        if (order.isPaymentPending()) {
            if (checkOnSale) {
                validateOnSale(order);
            }
            if (!groupPurchaseAcceptsPayment(order, paidAt, true)) {
                throw new ServiceException(OrderErrorCode.ORDER_GROUP_PURCHASE_PAYMENT_CLOSED);
            }
        }
        if (usedCoupon != null && order.isPaymentPending()) {
            order.applyCoupon(usedCoupon.memberCouponId(), usedCoupon.discountAmount(), usedCoupon.platformBurden(),
                    usedCoupon.sellerId(), OrderCouponRule.of(usedCoupon.discountType(), usedCoupon.discountValue(),
                            usedCoupon.maxDiscountAmount(), usedCoupon.minOrderAmount()));
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
                                item.getUnitPrice(),
                                order.sellerDiscountOf(item),
                                order.platformDiscountOf(item)))
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
    private void validateOnSale(Order order) {
        validateProducts(order.getItems().stream().map(OrderItem::getInventoryId).toList(), OrderProductInfo::onSale);
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
     * <p>같은 주문의 결제는 한 번에 하나만 진행한다: 주문 행을 잠그고 결제 진행 표시를 건 요청만 선점·쿠폰 사용·차감·반영하고,
     * 표시가 살아 있는 동안 들어온 요청은 409(ORDER_PAYMENT_IN_PROGRESS)로 바로 거절한다.
     * 그래서 실패한 요청이 진행 중인 다른 요청의 재고 선점을 푸는 일이 없다(선점은 주문 단위라 누가 잡았는지 구분하지 않는다).
     *
     * <p>순서: [트랜잭션] 표시 + 선점 + 쓸 쿠폰 기록 → 쿠폰 사용 → 차감 → 판매 확인 → [트랜잭션] 반영(DB만).
     * 쿠폰을 차감 전에 써서, 돈이 나간 뒤 쿠폰 때문에 차감을 되돌리는 일이 없게 한다. 차감 금액은 실제로 쓴 쿠폰의 할인으로 정한다.
     */
    PaymentResult pay(Long orderId, Long memberId, Long memberCouponId, LocalDateTime now) {
        var existing = orderPaymentClient.findPayment(PaymentOrderType.INDIVIDUAL_GROUP, orderId, memberId)
                .filter(PaymentResult::isPaid);
        if (existing.isPresent()) {
            // 이미 차감됨(응답을 못 받았던 재시도 등): 반영만 한다.
            // 쿠폰은 그 차감 때 기록해 둔 쿠폰을 쓴다(차감 금액이 그 할인으로 정해졌다). 같은 주문에 다시 쓰면 같은 결과다
            LocalDateTime lockedUntil = transaction.execute(status -> lockPayment(orderId, memberId, now, false, null));
            Order order = findWithItems(orderId);
            Long couponId = order.getMemberCouponId() != null ? order.getMemberCouponId() : memberCouponId;
            return applyOrCompensate(orderId, order, memberId, existing.get(),
                    () -> order.isPaymentPending() ? useCoupon(orderId, order, memberId, couponId) : null, lockedUntil);
        }
        // 잠그기 전에 확인 (주문 상품은 결제 대기 중 바뀌지 않는다)
        Order order = findWithItems(orderId);
        orderSupport.validateBuyer(order, memberId);
        // 공동구매 사본의 상품 목록은 지연 로딩이라 세션 안에서 확인한다 (pay()는 트랜잭션 밖에서 불린다)
        if (!order.canAcceptPayment(now)
                || !Boolean.TRUE.equals(transaction.execute(status -> groupPurchaseAcceptsPayment(order, now, false)))) {
            throw new ServiceException(OrderErrorCode.ORDER_NOT_PAYABLE);
        }
        validatePurchasable(order.getItems().stream().map(OrderItem::getInventoryId).toList());
        // 결제 진행 표시 + 재고 선점 + 쓸 쿠폰 기록을 한 트랜잭션으로 커밋. 부족하면 ORDER_OUT_OF_STOCK(표시 없이 롤백, 주문 취소는 호출한 쪽이 따로 한다)
        LocalDateTime lockedUntil = transaction.execute(status -> lockPayment(orderId, memberId, now, true, memberCouponId));
        CouponQuote usedCoupon;
        try {
            usedCoupon = useCoupon(orderId, order, memberId, memberCouponId);
        } catch (RuntimeException e) {
            // 쿠폰을 못 썼거나 썼는지 모른다. 아직 차감 전이라 선점을 풀고 쿠폰 복구를 요청한다(쓰지 않았으면 복구는 아무 일도 하지 않는다)
            releaseStock(orderId);
            releasePayment(orderId, lockedUntil);
            throw e;
        }
        long discount = usedCoupon == null ? 0 : usedCoupon.discountAmount();
        PaymentResult payment;
        try {
            payment = orderPaymentClient.charge(
                    PaymentOrderType.INDIVIDUAL_GROUP, orderId, memberId, order.getTotalAmount() - discount);
        } catch (InternalServerUnavailableException e) {
            // 결제됐는지 모른다 → 선점도 표시도 쿠폰 기록도 그대로(표시는 만료, 재시도가 반영하거나 결제 기한 취소가 정리·쿠폰 복구)
            throw e;
        } catch (ServiceException e) {
            // 같은 키의 차감이 아직 진행 중이면 결과를 모르는 것과 같다 → 그대로 둔다
            if (!OrderErrorCode.ORDER_PAYMENT_IN_PROGRESS.resultCode().equals(e.getRsData().getResultCode())) {
                releaseStock(orderId);   // 결제 실패가 확실 → 선점 해제·쿠폰 복구 (표시를 쥔 요청만 여기 온다)
                releasePayment(orderId, lockedUntil);
            }
            throw e;
        }
        CouponQuote applied = usedCoupon;
        return applyOrCompensate(orderId, order, memberId, payment, () -> applied, lockedUntil);
    }

    private Order findWithItems(Long orderId) {
        return orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new ServiceException(OrderErrorCode.ORDER_NOT_FOUND));
    }

    // 상품 서버에서 쿠폰을 이 주문에 사용 처리한다(트랜잭션 밖). 같은 주문에 다시 부르면 같은 결과다
    private CouponQuote useCoupon(Long orderId, Order order, Long memberId, Long memberCouponId) {
        if (memberCouponId == null) {
            return null;
        }
        return orderCouponClient.use(memberId, memberCouponId, orderId, order.couponTarget());
    }

    // 주문 행을 잠그고 결제 진행 표시를 건다. reserve면 결제 가능 여부를 다시 보고 재고를 선점하고, 이번 결제에 쓸 쿠폰을 기록한다
    // (행을 잠근 채라 같은 주문 선점이 겹치지 않는다). 앞선 시도가 다른 쿠폰을 기록해 뒀으면 그 쿠폰은 복구를 요청한다
    private LocalDateTime lockPayment(Long orderId, Long memberId, LocalDateTime now, boolean reserve,
                                      Long memberCouponId) {
        Order order = orderSupport.getOrderForUpdate(orderId);
        orderSupport.validateBuyer(order, memberId);
        if (reserve && !order.canAcceptPayment(now)) {
            throw new ServiceException(OrderErrorCode.ORDER_NOT_PAYABLE);
        }
        LocalDateTime lockedUntil = order.lockPayment(now);
        if (reserve) {
            Long replaced = order.recordPaymentCoupon(memberCouponId);
            if (replaced != null) {
                requestCouponRestore(orderId, order, replaced);
            }
            reserveStock(orderId, order);
        }
        return lockedUntil;
    }

    // 결제가 실패로 끝났을 때: 이번 결제에 기록한 쿠폰의 복구를 요청하고 진행 표시를 푼다(주문과 함께 커밋되는 이벤트).
    // 실패해도 원래 오류를 가리지 않게 로그만 남긴다(표시는 만료되면 풀리고, 쿠폰 기록은 결제 기한 취소 때 돌려준다)
    private void releasePayment(Long orderId, LocalDateTime lockedUntil) {
        try {
            transaction.executeWithoutResult(status -> {
                Order order = orderSupport.getOrderForUpdate(orderId);
                Long couponId = order.releasePaymentCoupon();
                if (couponId != null) {
                    requestCouponRestore(orderId, order, couponId);
                }
                order.unlockPayment(lockedUntil);
            });
        } catch (RuntimeException e) {
            log.warn("결제 실패 정리(쿠폰 복구 요청·진행 표시 해제) 실패 — 표시는 {} 뒤 풀리고 쿠폰은 결제 기한 취소 때 돌려준다: 주문 #{}",
                    Order.PAYMENT_LOCK_PERIOD, orderId, e);
        }
    }

    private void requestCouponRestore(Long orderId, Order order, Long memberCouponId) {
        eventPublisher.publish(new OrderCouponRestoreRequestedEvent(orderId, order.getBuyerMemberId(),
                memberCouponId, OrderCouponClient.ORDER_TYPE));
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

    // 차감 뒤: (이미 차감된 재시도면 쿠폰 사용) → 판매 중인지 확인 → 반영 트랜잭션(DB만).
    // 결제 완료로 바꾸면 진행 표시도 같이 지워진다(markPaid). 실패하면 정리한 뒤 표시를 푼다:
    // 차감은 이미 확정이라 다음 요청은 같은 결제를 받아 반영만 하므로 기다리게 할 이유가 없다
    private PaymentResult applyOrCompensate(Long orderId, Order order, Long memberId, PaymentResult payment,
                                            Supplier<CouponQuote> coupon, LocalDateTime lockedUntil) {
        try {
            CouponQuote usedCoupon = coupon.get();
            if (order.isPaymentPending()) {
                // 결제 직후라 이 주문의 재고 선점이 이미 잡혀 있다. 남은 수량은 보지 않고 판매 중인지만 다시 본다
                validateOnSale(order);
            }
            transaction.executeWithoutResult(status -> applyPayment(
                    orderId, memberId, payment.paymentId(), payment.paidAt(), usedCoupon, payment.amount(), false));
            return payment;
        } catch (InternalServerUnavailableException e) {
            // 반영하지 못했지만 차감은 남아 있다 → 쿠폰 기록은 그대로 두고 표시만 푼다(재시도가 같은 차감·쿠폰으로 반영한다)
            unlockPayment(orderId, lockedUntil);
            throw e;
        } catch (ServiceException e) {
            orderPaymentClient.compensateCharge(PaymentOrderType.INDIVIDUAL_GROUP, orderId, memberId);
            releaseStock(orderId);
            releasePayment(orderId, lockedUntil);
            throw e;
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
