package com.cartmate.cartmate.boundedContext.orderService.order.domain.personal;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.LinkedHashMap;
import com.cartmate.cartmate.shared.coupon.dto.CouponOrderTarget;
import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import com.cartmate.cartmate.shared.order.OrderCancelReason;
import com.cartmate.cartmate.shared.order.OrderStatus;
import com.cartmate.cartmate.shared.order.OrderType;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.DeliveryAddress;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.itemcancel.CouponMode;
import com.cartmate.cartmate.boundedContext.orderService.order.exception.OrderErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

@Getter
@Entity
@Table(
        name = "orders",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_orders_order_number", columnNames = "order_number"),
                @UniqueConstraint(name = Order.PENDING_BUYER_INDIVIDUAL_GROUP_UNIQUE,
                        columnNames = {"buyer_member_id", "pending_individual_group_purchase_id"})
        },
        indexes = {
                @Index(name = "idx_orders_individual_group_purchase_status", columnList = "individual_group_purchase_id, status"),
                @Index(name = "idx_orders_buyer_created", columnList = "buyer_member_id, created_at")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    public static final Duration PAYMENT_PERIOD = Duration.ofMinutes(30);
    public static final String PENDING_BUYER_INDIVIDUAL_GROUP_UNIQUE = "uk_orders_pending_buyer_individual_group";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    private OrderType orderType;

    @Column(nullable = false)
    private Long buyerMemberId;

    private Long individualGroupPurchaseId;

    @Column(name = "pending_individual_group_purchase_id")
    private Long pendingIndividualGroupPurchaseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(30)")
    private OrderCancelReason cancelReason;

    @Column(nullable = false)
    private long totalAmount;

    @Column(nullable = false)
    private LocalDateTime paymentDeadlineAt;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "receiverName", column = @Column(name = "receiver_name", length = 50, nullable = false)),
            @AttributeOverride(name = "receiverPhone", column = @Column(name = "receiver_phone", length = 20, nullable = false)),
            @AttributeOverride(name = "zipCode", column = @Column(name = "zip_code", length = 10, nullable = false)),
            @AttributeOverride(name = "address", column = @Column(name = "address", length = 255, nullable = false))
    })
    private DeliveryAddress deliveryAddress;

    private Long paymentId;

    private LocalDateTime paidAt;

    private LocalDateTime canceledAt;

    @Column(name = "cancel_request_seq", nullable = false, columnDefinition = "int not null default 0")
    private int cancelRequestSeq;

    @Column(name = "cancel_requested_at")
    private LocalDateTime cancelRequestedAt;

    @Column(name = "cancel_last_sent_at")
    private LocalDateTime cancelLastSentAt;

    @Column(name = "cancel_reject_code", length = 100)
    private String cancelRejectCode;

    @Column(name = "member_coupon_id")
    private Long memberCouponId;

    @Column(name = "discount_amount", nullable = false)
    private long discountAmount;

    @Column(name = "discount_burden", columnDefinition = "varchar(20)")
    @Enumerated(EnumType.STRING)
    private OrderDiscountBurden discountBurden;

    @Column(name = "discount_seller_id")
    private Long discountSellerId;

    @Column(name = "item_refunded_amount", nullable = false)
    private long itemRefundedAmount;

    @Column(name = "partially_canceled", nullable = false)
    private boolean partiallyCanceled;

    @Embedded
    private OrderCouponRule couponRule;

    // 한 주문에서 쿠폰은 한 번만 돌려준다
    @Column(name = "coupon_returned", nullable = false)
    private boolean couponReturned;

    @OrderBy("id")
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private Order(String orderNumber, OrderType orderType, Long buyerMemberId, Long individualGroupPurchaseId,
                  List<OrderLine> lines, DeliveryAddress deliveryAddress, LocalDateTime paymentDeadlineAt) {
        if (lines == null || lines.isEmpty()) {
            throw new ServiceException(OrderErrorCode.ORDER_NO_ITEMS);
        }
        if (deliveryAddress == null) {
            throw new ServiceException(OrderErrorCode.ORDER_INVALID_DELIVERY_ADDRESS);
        }
        this.orderNumber = orderNumber;
        this.orderType = orderType;
        this.buyerMemberId = buyerMemberId;
        this.individualGroupPurchaseId = individualGroupPurchaseId;
        this.pendingIndividualGroupPurchaseId = orderType == OrderType.INDIVIDUAL_GROUP ? individualGroupPurchaseId : null;
        this.status = OrderStatus.PAYMENT_PENDING;
        this.paymentDeadlineAt = paymentDeadlineAt;
        this.deliveryAddress = deliveryAddress;

        lines.forEach(line -> items.add(OrderItem.create(this, line)));
        this.totalAmount = items.stream().mapToLong(OrderItem::amount).sum();
    }

    public static Order createPersonal(String orderNumber, Long buyerMemberId, List<OrderLine> lines,
                                       DeliveryAddress deliveryAddress, LocalDateTime now) {
        return new Order(orderNumber, OrderType.PERSONAL, buyerMemberId, null,
                lines, deliveryAddress, now.plus(PAYMENT_PERIOD));
    }

    public static Order createIndividualGroup(String orderNumber, Long buyerMemberId, Long individualGroupPurchaseId,
                                              LocalDateTime saleDeadlineAt, List<OrderLine> lines,
                                              DeliveryAddress deliveryAddress, LocalDateTime now) {
        LocalDateTime paymentDeadlineAt = now.plus(PAYMENT_PERIOD);
        if (saleDeadlineAt.isBefore(paymentDeadlineAt)) {
            paymentDeadlineAt = saleDeadlineAt;
        }
        return new Order(orderNumber, OrderType.INDIVIDUAL_GROUP, buyerMemberId, individualGroupPurchaseId,
                lines, deliveryAddress, paymentDeadlineAt);
    }

    public boolean canAcceptPayment(LocalDateTime paidAt) {
        return status == OrderStatus.PAYMENT_PENDING && !paidAt.isAfter(paymentDeadlineAt);
    }

    public boolean markPaid(Long paymentId, LocalDateTime paidAt) {
        if (paymentId == null) {
            throw new ServiceException(OrderErrorCode.ORDER_PAYMENT_ID_REQUIRED);
        }
        if (isPaid() && paymentId.equals(this.paymentId)) {
            return false;
        }
        validatePaymentPending();
        if (!canAcceptPayment(paidAt)) {
            throw new ServiceException(OrderErrorCode.ORDER_PAYMENT_EXPIRED);
        }
        this.status = OrderStatus.PAID;
        this.paymentId = paymentId;
        this.paidAt = paidAt;
        releasePending();
        return true;
    }

    public BuyerCancelResult cancelByBuyer(Long requesterId, LocalDateTime now) {
        if (!isBuyer(requesterId)) {
            throw new ServiceException(OrderErrorCode.ORDER_BUYER_ONLY);
        }
        if (status == OrderStatus.CANCELED) {
            throw new ServiceException(OrderErrorCode.ORDER_ALREADY_CANCELED);
        }
        if (status == OrderStatus.CANCEL_REQUESTED) {
            this.cancelLastSentAt = now;
            return BuyerCancelResult.RESENT;
        }
        if (status == OrderStatus.PAID) {
            this.status = OrderStatus.CANCEL_REQUESTED;
            this.cancelRequestSeq++;
            this.cancelRequestedAt = now;
            this.cancelLastSentAt = now;
            this.cancelRejectCode = null;
            return BuyerCancelResult.REQUESTED;
        }
        cancel(OrderCancelReason.BUYER_CANCELED, now);
        return BuyerCancelResult.CANCELED;
    }

    public boolean resendCancelRequest(LocalDateTime now, Duration resendAfter) {
        if (status != OrderStatus.CANCEL_REQUESTED || cancelLastSentAt == null
                || cancelLastSentAt.plus(resendAfter).isAfter(now)) {
            return false;
        }
        this.cancelLastSentAt = now;
        return true;
    }

    public boolean confirmCancel(int requestSeq, LocalDateTime now) {
        if (status != OrderStatus.CANCEL_REQUESTED || requestSeq != cancelRequestSeq) {
            return false;
        }
        cancel(OrderCancelReason.BUYER_CANCELED, now);
        return true;
    }

    public boolean rejectCancel(int requestSeq, String rejectCode) {
        if (status != OrderStatus.CANCEL_REQUESTED || requestSeq != cancelRequestSeq) {
            return false;
        }
        this.status = OrderStatus.PAID;
        this.cancelRejectCode = rejectCode;
        return true;
    }

    public boolean isPaid() {
        return status == OrderStatus.PAID || status == OrderStatus.CANCEL_REQUESTED;
    }

    public boolean cancelByDeadline(LocalDateTime now) {
        if (status != OrderStatus.PAYMENT_PENDING || now.isBefore(paymentDeadlineAt)) {
            return false;
        }
        cancel(OrderCancelReason.PAYMENT_EXPIRED, now);
        return true;
    }

    public boolean cancelByOutOfStock(LocalDateTime now) {
        validatePaymentPending();
        cancel(OrderCancelReason.OUT_OF_STOCK, now);
        return true;
    }

    public boolean cancelBySalesStopped(LocalDateTime now) {
        if (status != OrderStatus.PAYMENT_PENDING) {
            return false;
        }
        cancel(OrderCancelReason.SALES_STOPPED, now);
        return true;
    }

    public boolean cancelByGroupPurchaseClosed(LocalDateTime now) {
        if (status != OrderStatus.PAYMENT_PENDING) {
            return false;
        }
        cancel(OrderCancelReason.GROUP_PURCHASE_CLOSED, now);
        return true;
    }

    public void applyCoupon(Long memberCouponId, long discountAmount, boolean platformBurden, Long sellerId) {
        applyCoupon(memberCouponId, discountAmount, platformBurden, sellerId, null);
    }

    public void applyCoupon(Long memberCouponId, long discountAmount, boolean platformBurden, Long sellerId,
                            OrderCouponRule couponRule) {
        if (this.memberCouponId != null) {
            if (this.memberCouponId.equals(memberCouponId) && this.discountAmount == discountAmount) {
                return;
            }
            throw new ServiceException(OrderErrorCode.ORDER_COUPON_ALREADY_APPLIED);
        }
        validatePaymentPending();
        if (discountAmount < 0 || discountAmount > totalAmount) {
            throw new ServiceException(OrderErrorCode.ORDER_INVALID_DISCOUNT);
        }
        this.memberCouponId = memberCouponId;
        this.discountAmount = discountAmount;
        this.discountBurden = platformBurden ? OrderDiscountBurden.PLATFORM : OrderDiscountBurden.SELLER;
        this.discountSellerId = platformBurden ? null : sellerId;
        this.couponRule = couponRule;
    }

    public long payAmount() {
        return totalAmount - discountAmount;
    }

    public long remainingRefundAmount() {
        return payAmount() - itemRefundedAmount;
    }

    public boolean hasCoupon() {
        return memberCouponId != null;
    }

    public CouponOrderTarget couponTarget() {
        Map<Long, Long> bySeller = new LinkedHashMap<>();
        for (OrderItem item : items) {
            if (item.getSellerId() != null) {
                bySeller.merge(item.getSellerId(), item.amount(), Long::sum);
            }
        }
        return new CouponOrderTarget(totalAmount, bySeller.entrySet().stream()
                .map(e -> new CouponOrderTarget.SellerAmount(e.getKey(), e.getValue()))
                .toList());
    }

    public boolean couponRestorable() {
        return hasCoupon() && status == OrderStatus.CANCELED && !couponReturned;
    }

    public void markCouponReturned() {
        this.couponReturned = true;
    }

    private long discountShareOf(Long sellerId, long itemsAmount) {
        if (discountAmount == 0 || itemsAmount == 0) {
            return 0;
        }
        if (discountBurden == OrderDiscountBurden.SELLER) {
            if (!sellerId.equals(discountSellerId)) {
                return 0;
            }
            long sellerTotal = items.stream().filter(i -> sellerId.equals(i.getSellerId())).mapToLong(OrderItem::amount).sum();
            return discountAmount * itemsAmount / sellerTotal;
        }
        return discountAmount * itemsAmount / totalAmount;
    }

    // 한 판매자의 항목 중 고른 것(null이면 남은 전부)을 취소한다. 판매글 내림·판매자 사유 취소가 쓴다.
    public ItemCancelOutcome cancelItems(Long sellerId, Collection<Long> orderItemIds, DeliveryCancelReason reason,
                                         CouponMode couponMode, LocalDateTime now) {
        return cancelItems(Collections.singletonMap(sellerId, orderItemIds), reason, couponMode, now);
    }

    // 판매자별로 고른 항목(null이면 그 판매자의 남은 전부)을 한 번에 취소한다. 다른 판매자의 항목이나 이미 취소된 항목은 건너뛴다.
    // 환불 합계는 모두 뺀 최종 상태로 한 번에 계산해야 판매자 순서와 상관없이 같다. 합계는 판매자별 취소 상품 금액 비율로 나눈다.
    // 모든 항목이 취소되면 주문도 취소하고 남은 결제 금액을 모두 돌려준다.
    public ItemCancelOutcome cancelItems(Map<Long, ? extends Collection<Long>> itemIdsBySeller, DeliveryCancelReason reason,
                                         CouponMode couponMode, LocalDateTime now) {
        if (!isPaid()) {
            return ItemCancelOutcome.none();
        }
        Map<Long, List<OrderItem>> targetsBySeller = new LinkedHashMap<>();
        itemIdsBySeller.forEach((sellerId, orderItemIds) -> {
            List<OrderItem> targets = items.stream()
                    .filter(item -> sellerId.equals(item.getSellerId()) && !item.isCanceled())
                    .filter(item -> orderItemIds == null || orderItemIds.contains(item.getId()))
                    .toList();
            if (!targets.isEmpty()) {
                targetsBySeller.put(sellerId, targets);
            }
        });
        if (targetsBySeller.isEmpty()) {
            return ItemCancelOutcome.none();
        }
        List<OrderItem> allTargets = targetsBySeller.values().stream().flatMap(List::stream).toList();
        CancelCalc calc = calculate(allTargets, OrderItem::isCanceled, remainingRefundAmount(), couponMode);
        allTargets.forEach(item -> item.cancel(reason, now));
        boolean couponReturnable = couponReturnsWith(calc);
        if (calc.orderCanceled()) {
            cancel(orderCancelReasonOfItems(), now);
        } else {
            partiallyCanceled = true;
        }
        if (couponReturnable) {
            couponReturned = true;
        }
        itemRefundedAmount += calc.refund();
        return new ItemCancelOutcome(splitBySeller(targetsBySeller, calc.refund(), amountOf(allTargets)),
                calc.refund(), calc.orderCanceled(), couponReturnable);
    }

    // 판매자 몫 환불 이벤트는 판매자마다 나가므로 합계를 취소 상품 금액 비율로 나눈다. 끝전은 마지막 판매자에게 붙인다
    private static List<ItemCancelOutcome.SellerPart> splitBySeller(Map<Long, List<OrderItem>> targetsBySeller,
                                                                    long refund, long itemsAmount) {
        List<ItemCancelOutcome.SellerPart> parts = new ArrayList<>();
        long given = 0;
        int index = 0;
        for (Map.Entry<Long, List<OrderItem>> entry : targetsBySeller.entrySet()) {
            boolean last = ++index == targetsBySeller.size();
            long share = last || itemsAmount == 0 ? refund - given : refund * amountOf(entry.getValue()) / itemsAmount;
            given += share;
            parts.add(new ItemCancelOutcome.SellerPart(entry.getKey(), entry.getValue(), share));
        }
        return parts;
    }

    // 고른 상품을 모두 취소한다고 보고 환불 금액과 쿠폰 반환 여부를 계산한다. 주문은 바꾸지 않는다.
    // 쿠폰 최소 금액이 깨져 구매자가 오히려 더 내야 하는 취소는 받지 않는다(싼 상품을 끼워 조건을 채운 뒤 빼면 할인만 남기 때문).
    public OrderCancelPreview previewBuyerCancel(Long requesterId, Collection<Long> orderItemIds) {
        List<OrderItem> targets = buyerCancelTargets(requesterId, orderItemIds);
        if (!isPaid()) {
            return new OrderCancelPreview(0, false);
        }
        CancelCalc calc = calculate(targets, OrderItem::isCanceled, remainingRefundAmount(), CouponMode.RECALCULATE);
        if (calc.underpaid()) {
            throw new ServiceException(OrderErrorCode.ORDER_CANCEL_COUPON_MINIMUM);
        }
        return new OrderCancelPreview(calc.refund(), couponReturnsWith(calc));
    }

    // underpaid: 다시 계산하면 구매자가 더 내야 하는 경우. 추가로 받지 않으므로 환불은 0원
    private record CancelCalc(long refund, boolean orderCanceled, long remainingDiscount, boolean underpaid) {
    }

    // 이번에 뺄 항목 전체의 환불 금액. canceledBefore는 이번 취소 전에 이미 취소된 항목인지.
    // 구매자 취소는 남은 상품으로 쿠폰 할인을 다시 계산하고, 판매자 탓 취소는 할인을 비율로 나눈다.
    // 어느 쪽이든 지금 남은 할인보다 많이 빼지 않고, 지금까지 낸 돈보다 많이 돌려주지 않는다.
    private CancelCalc calculate(List<OrderItem> targets, Predicate<OrderItem> canceledBefore,
                                 long netPaid, CouponMode couponMode) {
        long itemsAmount = amountOf(targets);
        List<OrderItem> remaining = items.stream()
                .filter(item -> !canceledBefore.test(item) && !targets.contains(item))
                .toList();
        if (remaining.isEmpty()) {
            return new CancelCalc(netPaid, true, 0, false);
        }
        long remainingAfter = amountOf(remaining);
        long currentDiscount = Math.max(0, remainingAfter + itemsAmount - netPaid);
        long refund;
        boolean underpaid = false;
        if (couponMode == CouponMode.RECALCULATE && couponRule != null) {
            long eligible = discountBurden == OrderDiscountBurden.SELLER
                    ? amountOf(remaining.stream().filter(item -> discountSellerId.equals(item.getSellerId())).toList())
                    : remainingAfter;
            long newDiscount = Math.min(couponRule.discountFor(eligible), currentDiscount);
            long raw = netPaid - (remainingAfter - newDiscount);
            underpaid = raw < 0;
            refund = Math.max(0, raw);
        } else {
            refund = itemsAmount - Math.min(discountShareOf(targets), currentDiscount);
        }
        refund = Math.min(refund, netPaid);
        return new CancelCalc(refund, false, remainingAfter - (netPaid - refund), underpaid);
    }

    private long discountShareOf(List<OrderItem> targets) {
        Map<Long, Long> amountBySeller = new LinkedHashMap<>();
        targets.forEach(item -> amountBySeller.merge(item.getSellerId(), item.amount(), Long::sum));
        return amountBySeller.entrySet().stream()
                .mapToLong(entry -> discountShareOf(entry.getKey(), entry.getValue()))
                .sum();
    }

    // 쿠폰은 그 주문에서 혜택이 0이 되는 순간 한 번 돌려준다: 누가 취소했든 실제로 남은 할인이 0이 될 때(주문 전부 취소 포함)
    private boolean couponReturnsWith(CancelCalc calc) {
        return hasCoupon() && !couponReturned && (calc.orderCanceled() || calc.remainingDiscount() == 0);
    }

    private static long amountOf(List<OrderItem> orderItems) {
        return orderItems.stream().mapToLong(OrderItem::amount).sum();
    }

    // 취소된 항목의 사유가 모두 같은 갈래면 그 사유, 섞였으면 여러 사유
    private OrderCancelReason orderCancelReasonOfItems() {
        Set<OrderCancelReason> reasons = new HashSet<>();
        items.forEach(item -> reasons.add(orderCancelReasonOf(item.getCancelReason())));
        return reasons.size() == 1 ? reasons.iterator().next() : OrderCancelReason.MULTIPLE_REASONS;
    }

    // 판매 중지(판매글 내림)나 판매자 사유 취소로 한 판매자의 남은 항목을 모두 취소하고, 그 몫의 환불 금액을 돌려준다
    public long cancelSellerItems(Long sellerId, DeliveryCancelReason reason, LocalDateTime now) {
        return cancelItems(sellerId, null, reason, CouponMode.PROPORTIONAL, now).refundAmount();
    }

    private static OrderCancelReason orderCancelReasonOf(DeliveryCancelReason reason) {
        return switch (reason) {
            case BUYER_CANCELED -> OrderCancelReason.BUYER_CANCELED;
            case SALES_STOPPED -> OrderCancelReason.SALES_STOPPED;
            default -> OrderCancelReason.SELLER_CANCELED;
        };
    }

    // 결제 완료 주문에서 구매자가 고른 항목을 확인한다. 이 주문의 취소되지 않은 항목이어야 하고 같은 항목을 두 번 고를 수 없다.
    public List<OrderItem> buyerCancelTargets(Long requesterId, Collection<Long> orderItemIds) {
        if (!isBuyer(requesterId)) {
            throw new ServiceException(OrderErrorCode.ORDER_BUYER_ONLY);
        }
        if (status == OrderStatus.CANCELED) {
            throw new ServiceException(OrderErrorCode.ORDER_ALREADY_CANCELED);
        }
        if (orderItemIds == null || orderItemIds.isEmpty() || Set.copyOf(orderItemIds).size() != orderItemIds.size()) {
            throw new ServiceException(OrderErrorCode.ORDER_CANCEL_ITEM_INVALID);
        }
        List<OrderItem> targets = items.stream()
                .filter(item -> orderItemIds.contains(item.getId()) && !item.isCanceled() && item.getSellerId() != null)
                .toList();
        if (targets.size() != orderItemIds.size()) {
            throw new ServiceException(OrderErrorCode.ORDER_CANCEL_ITEM_INVALID);
        }
        return targets;
    }

    public boolean isBuyer(Long memberId) {
        return buyerMemberId.equals(memberId);
    }

    public boolean isIndividualGroup() {
        return orderType == OrderType.INDIVIDUAL_GROUP;
    }

    public boolean isPaymentPending() {
        return status == OrderStatus.PAYMENT_PENDING;
    }

    public List<Long> productIds() {
        return items.stream().map(OrderItem::getProductId).distinct().toList();
    }

    private void cancel(OrderCancelReason reason, LocalDateTime now) {
        this.status = OrderStatus.CANCELED;
        this.cancelReason = reason;
        this.canceledAt = now;
        releasePending();
    }

    private void releasePending() {
        this.pendingIndividualGroupPurchaseId = null;
        items.forEach(OrderItem::releasePending);
    }

    private void validatePaymentPending() {
        if (status != OrderStatus.PAYMENT_PENDING) {
            throw new ServiceException(OrderErrorCode.ORDER_NOT_PAYMENT_PENDING);
        }
    }

    public record OrderLine(
            Long productId,
            Long inventoryId,
            Long sellerId,
            String productName,
            String optionName,
            long unitPrice,
            int quantity
    ) {
    }
}
