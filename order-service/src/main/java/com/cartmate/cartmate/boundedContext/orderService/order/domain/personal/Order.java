package com.cartmate.cartmate.boundedContext.orderService.order.domain.personal;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.LinkedHashMap;
import com.cartmate.cartmate.shared.coupon.dto.CouponOrderTarget;
import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import com.cartmate.cartmate.shared.delivery.event.PendingReturn;
import com.cartmate.cartmate.shared.order.OrderCancelReason;
import com.cartmate.cartmate.shared.order.OrderStatus;
import com.cartmate.cartmate.shared.order.OrderType;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.DeliveryAddress;
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
import java.time.temporal.ChronoUnit;
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
    // 결제 한 번(내부 HTTP 최대 7번 × 약 7초)보다 길게. 서버가 도중에 죽어도 이 시간이 지나면 다시 결제할 수 있다
    public static final Duration PAYMENT_LOCK_PERIOD = Duration.ofSeconds(60);
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

    // 바로구매 주문인지. 바로구매는 장바구니와 무관해서 결제 뒤 장바구니를 정리하지 않는다
    @Column(name = "direct_order", nullable = false)
    private boolean directOrder;

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

    // 결제 진행 표시. 이 시각 전까지는 다른 결제 요청이 들어올 수 없다(같은 주문 동시 결제 → 409).
    // 값 자체가 잠근 요청의 표식이라, 자기가 건 값일 때만 푼다
    @Column(name = "payment_locked_until")
    private LocalDateTime paymentLockedUntil;

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
                  boolean directOrder, List<OrderLine> lines, DeliveryAddress deliveryAddress,
                  LocalDateTime paymentDeadlineAt) {
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
        this.directOrder = directOrder;
        this.status = OrderStatus.PAYMENT_PENDING;
        this.paymentDeadlineAt = paymentDeadlineAt;
        this.deliveryAddress = deliveryAddress;

        lines.forEach(line -> items.add(OrderItem.create(this, line)));
        this.totalAmount = items.stream().mapToLong(OrderItem::amount).sum();
    }

    // 장바구니에서 고른 항목으로 만드는 개인 주문
    public static Order createPersonal(String orderNumber, Long buyerMemberId, List<OrderLine> lines,
                                       DeliveryAddress deliveryAddress, LocalDateTime now) {
        return new Order(orderNumber, OrderType.PERSONAL, buyerMemberId, null, false,
                lines, deliveryAddress, now.plus(PAYMENT_PERIOD));
    }

    // 바로구매: 장바구니를 거치지 않는 개인 주문
    public static Order createDirect(String orderNumber, Long buyerMemberId, OrderLine line,
                                     DeliveryAddress deliveryAddress, LocalDateTime now) {
        return new Order(orderNumber, OrderType.PERSONAL, buyerMemberId, null, true,
                List.of(line), deliveryAddress, now.plus(PAYMENT_PERIOD));
    }

    public static Order createIndividualGroup(String orderNumber, Long buyerMemberId, Long individualGroupPurchaseId,
                                              LocalDateTime saleDeadlineAt, List<OrderLine> lines,
                                              DeliveryAddress deliveryAddress, LocalDateTime now) {
        LocalDateTime paymentDeadlineAt = now.plus(PAYMENT_PERIOD);
        if (saleDeadlineAt.isBefore(paymentDeadlineAt)) {
            paymentDeadlineAt = saleDeadlineAt;
        }
        return new Order(orderNumber, OrderType.INDIVIDUAL_GROUP, buyerMemberId, individualGroupPurchaseId, false,
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
        this.paymentLockedUntil = null;
        releasePending();
        return true;
    }

    // 주문 행을 잠근(FOR UPDATE) 상태에서 부른다. 다른 결제가 진행 중이면 409, 아니면 잠그고 그 값을 돌려준다
    public LocalDateTime lockPayment(LocalDateTime now) {
        if (paymentLockedUntil != null && now.isBefore(paymentLockedUntil)) {
            throw new ServiceException(OrderErrorCode.ORDER_PAYMENT_IN_PROGRESS);
        }
        // DB 시각 정밀도와 상관없이 다시 읽어도 같은 값이 되게 밀리초로 자른다(풀 때 이 값으로 비교)
        this.paymentLockedUntil = now.plus(PAYMENT_LOCK_PERIOD).truncatedTo(ChronoUnit.MILLIS);
        return paymentLockedUntil;
    }

    // 주문 행을 잠근 상태에서 부른다. 자기가 건 표시일 때만 푼다
    public void unlockPayment(LocalDateTime lockedUntil) {
        if (lockedUntil != null && lockedUntil.equals(paymentLockedUntil)) {
            this.paymentLockedUntil = null;
        }
    }

    // 결제 진행 표시를 거는 트랜잭션에서 이번 결제에 쓸 쿠폰을 기록한다(없으면 null).
    // 쿠폰은 결제 반영 전에 상품 서버에서 사용 처리되므로, 결제가 끝나지 못하고 주문이 취소되면 이 기록으로 돌려준다.
    // 앞선 결제 시도가 다른 쿠폰을 기록해 뒀으면 그 쿠폰을 반환한다(호출한 쪽이 복구를 요청한다).
    public Long recordPaymentCoupon(Long memberCouponId) {
        validatePaymentPending();
        Long previous = this.memberCouponId;
        this.memberCouponId = memberCouponId;
        return previous != null && !previous.equals(memberCouponId) ? previous : null;
    }

    // 결제가 실패해 이번 결제에 기록한 쿠폰을 돌려줄 때 기록을 지우고 그 쿠폰 ID를 반환한다. 결제 대기 주문이 아니면 null
    public Long releasePaymentCoupon() {
        if (!isPaymentPending() || memberCouponId == null) {
            return null;
        }
        Long released = memberCouponId;
        this.memberCouponId = null;
        return released;
    }

    // 결제 대기 주문은 배송이 없으므로 구매자가 바로 취소한다. 결제 완료 주문은 상품을 골라 배송 서버에 먼저 요청한다
    public void cancelByBuyer(Long requesterId, LocalDateTime now) {
        if (!isBuyer(requesterId)) {
            throw new ServiceException(OrderErrorCode.ORDER_BUYER_ONLY);
        }
        if (status == OrderStatus.CANCELED) {
            throw new ServiceException(OrderErrorCode.ORDER_ALREADY_CANCELED);
        }
        validatePaymentPending();
        cancel(OrderCancelReason.BUYER_CANCELED, now);
    }

    public boolean isPaid() {
        return status == OrderStatus.PAID;
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
        if (discountBurden != null) {
            if (memberCouponId.equals(this.memberCouponId) && this.discountAmount == discountAmount) {
                return;
            }
            throw new ServiceException(OrderErrorCode.ORDER_COUPON_ALREADY_APPLIED);
        }
        // 결제 진행 때 기록한 쿠폰과 다른 쿠폰은 받지 않는다
        if (this.memberCouponId != null && !this.memberCouponId.equals(memberCouponId)) {
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
        Map<OrderItem, Long> shares = discountShares();
        items.forEach(item -> {
            long share = shares.getOrDefault(item, 0L);
            item.assignDiscount(platformBurden ? 0 : share, platformBurden ? share : 0);
        });
    }

    // 쿠폰 할인을 상품 줄에 나눈다. 판매자 부담이면 그 판매자 줄만, 플랫폼 부담이면 모든 줄이 대상이다.
    // 몫 = 할인 x 줄 금액 / 대상 합계(원 미만 버림), 끝전은 마지막(ID가 가장 큰) 대상 줄에 붙인다.
    // 결제 때 한 번 정해 저장하고, 이후 취소·반품 환불과 정산이 모두 이 값을 쓴다.
    private Map<OrderItem, Long> discountShares() {
        Map<OrderItem, Long> shares = new LinkedHashMap<>();
        if (discountAmount == 0 || discountBurden == null) {
            return shares;
        }
        List<OrderItem> targets = items.stream()
                .filter(item -> discountBurden == OrderDiscountBurden.PLATFORM
                        || (item.getSellerId() != null && item.getSellerId().equals(discountSellerId)))
                .toList();
        long targetAmount = amountOf(targets);
        if (targets.isEmpty() || targetAmount == 0) {
            return shares;
        }
        long given = 0;
        for (OrderItem item : targets) {
            long share = Math.multiplyExact(discountAmount, item.amount()) / targetAmount;
            shares.put(item, share);
            given += share;
        }
        shares.merge(targets.get(targets.size() - 1), discountAmount - given, Long::sum);
        return shares;
    }

    // 이 줄에 고정된 쿠폰 할인. 줄별 할인 컬럼이 생기기 전에 결제된 주문은 같은 규칙으로 계산한다
    public long discountOf(OrderItem item) {
        if (item.hasAssignedDiscount()) {
            return item.assignedDiscount();
        }
        return discountShares().getOrDefault(item, 0L);
    }

    // 이벤트에 싣는 줄별 판매자 부담 할인
    public long sellerDiscountOf(OrderItem item) {
        return discountBurden == OrderDiscountBurden.SELLER ? discountOf(item) : 0;
    }

    // 이벤트에 싣는 줄별 플랫폼 부담 할인
    public long platformDiscountOf(OrderItem item) {
        return discountBurden == OrderDiscountBurden.PLATFORM ? discountOf(item) : 0;
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

    // 한 판매자의 항목 중 고른 것(null이면 남은 전부)을 취소한다. 판매글 내림·판매자 사유 취소가 쓴다.
    public ItemCancelOutcome cancelItems(Long sellerId, Collection<Long> orderItemIds, DeliveryCancelReason reason,
                                         LocalDateTime now) {
        return cancelItems(Collections.singletonMap(sellerId, orderItemIds), reason, now);
    }

    // 판매자별로 고른 항목(null이면 그 판매자의 남은 전부)을 한 번에 취소한다. 다른 판매자의 항목이나 이미 취소된 항목은 건너뛴다.
    // 환불 합계는 모두 뺀 최종 상태로 한 번에 계산해야 판매자 순서와 상관없이 같다. 합계는 판매자별 취소 상품 금액 비율로 나눈다.
    // 모든 항목이 취소되면 주문도 취소하고 남은 결제 금액을 모두 돌려준다.
    public ItemCancelOutcome cancelItems(Map<Long, ? extends Collection<Long>> itemIdsBySeller, DeliveryCancelReason reason,
                                         LocalDateTime now) {
        if (!isPaid()) {
            return ItemCancelOutcome.none();
        }
        Map<Long, List<OrderItem>> targetsBySeller = new LinkedHashMap<>();
        itemIdsBySeller.forEach((sellerId, orderItemIds) -> {
            List<OrderItem> targets = items.stream()
                    .filter(item -> sellerId.equals(item.getSellerId()) && !item.isGone())
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
        CancelCalc calc = calculate(allTargets, OrderItem::isGone, remainingRefundAmount());
        allTargets.forEach(item -> item.cancel(reason, now));
        boolean couponReturnable = couponReturnsWith(calc);
        // 반품된 항목이 있는 주문은 남은 상품이 모두 취소돼도 취소 상태로 바꾸지 않는다(받아서 반품한 상품이 있다)
        if (calc.orderCanceled() && items.stream().allMatch(OrderItem::isCanceled)) {
            cancel(orderCancelReasonOfItems(), now);
        } else {
            partiallyCanceled = true;
        }
        if (couponReturnable) {
            couponReturned = true;
        }
        itemRefundedAmount += calc.refund();
        return new ItemCancelOutcome(splitBySeller(targetsBySeller, calc.refund()),
                calc.refund(), calc.orderCanceled(), couponReturnable);
    }

    // 판매자 몫 환불 이벤트는 판매자마다 나간다. 각 판매자 몫은 그 판매자 취소 상품의 (금액 - 고정 할인) 합이고,
    // 합계와의 차이(마지막 취소에서 남은 결제 금액 전부를 돌려줄 때 등)는 마지막 판매자에게 붙인다
    private List<ItemCancelOutcome.SellerPart> splitBySeller(Map<Long, List<OrderItem>> targetsBySeller, long refund) {
        List<ItemCancelOutcome.SellerPart> parts = new ArrayList<>();
        long given = 0;
        int index = 0;
        for (Map.Entry<Long, List<OrderItem>> entry : targetsBySeller.entrySet()) {
            boolean last = ++index == targetsBySeller.size();
            long net = entry.getValue().stream().mapToLong(item -> item.amount() - discountOf(item)).sum();
            long share = last ? refund - given : Math.min(net, refund - given);
            given += share;
            parts.add(new ItemCancelOutcome.SellerPart(entry.getKey(), entry.getValue(), share));
        }
        return parts;
    }

    // 고른 상품을 모두 취소한다고 보고 환불 금액과 쿠폰 반환 여부를 계산한다. 주문은 바꾸지 않는다.
    public OrderCancelPreview previewBuyerCancel(Long requesterId, Collection<Long> orderItemIds) {
        List<OrderItem> targets = buyerCancelTargets(requesterId, orderItemIds);
        if (!isPaid()) {
            return new OrderCancelPreview(0, false);
        }
        CancelCalc calc = calculate(targets, OrderItem::isGone, remainingRefundAmount());
        return new OrderCancelPreview(calc.refund(), couponReturnsWith(calc));
    }

    // 고른 상품을 반품한다고 보고 환불 금액을 계산한다. 주문은 바꾸지 않는다.
    public OrderReturnPreview previewReturn(Collection<Long> orderItemIds) {
        return previewReturn(orderItemIds, List.of());
    }

    // 같은 주문에서 처리 중인 다른 반품이 차례로 먼저 승인돼 환불된 것으로 보고 이번 반품을 계산한다. 주문은 바꾸지 않는다.
    // 각 반품이 서로를 모른 채 검사를 통과해도 차례로 환불되면 합쳐서 신청했을 때보다 더 돌려받을 수 있어서, 먼저 환불된다고 가정해 막는다.
    public OrderReturnPreview previewReturn(Collection<Long> orderItemIds, List<PendingReturn> pendingReturns) {
        List<OrderItem> targets = returnTargets(orderItemIds);
        Set<OrderItem> pendingGone = new HashSet<>();
        Predicate<OrderItem> gone = item -> item.isGone() || pendingGone.contains(item);
        long netPaid = remainingRefundAmount();
        for (PendingReturn pending : pendingReturns) {
            List<OrderItem> pendingTargets = items.stream()
                    .filter(item -> pending.orderItemIds().contains(item.getId()) && item.getSellerId() != null
                            && !gone.test(item) && !targets.contains(item))
                    .toList();
            if (pendingTargets.isEmpty()) {
                continue;
            }
            CancelCalc calc = calculate(pendingTargets, gone, netPaid);
            netPaid -= calc.refund();
            pendingGone.addAll(pendingTargets);
        }
        CancelCalc calc = calculate(targets, gone, netPaid);
        return new OrderReturnPreview(calc.refund());
    }

    // 구매자 본인의 반품 예상 환불
    public OrderReturnPreview previewBuyerReturn(Long requesterId, Collection<Long> orderItemIds) {
        if (!isBuyer(requesterId)) {
            throw new ServiceException(OrderErrorCode.ORDER_BUYER_ONLY);
        }
        return previewReturn(orderItemIds);
    }

    // 판매자가 승인한 반품을 반영한다.
    // 반품은 취소가 아니라 주문 상태는 바꾸지 않고, 쿠폰은 혜택이 0이 되는 순간 한 번 돌려준다.
    public OrderReturnOutcome applyReturn(Collection<Long> orderItemIds, LocalDateTime now) {
        List<OrderItem> targets = returnTargets(orderItemIds);
        CancelCalc calc = calculate(targets, OrderItem::isGone, remainingRefundAmount());
        targets.forEach(item -> item.markReturned(now));
        boolean couponReturnable = couponReturnsWith(calc);
        if (couponReturnable) {
            couponReturned = true;
        }
        itemRefundedAmount += calc.refund();
        return new OrderReturnOutcome(calc.refund(), couponReturnable);
    }

    // 결제 완료 주문에서 아직 빠지지 않은 항목이어야 하고, 같은 항목을 두 번 고를 수 없다.
    private List<OrderItem> returnTargets(Collection<Long> orderItemIds) {
        if (!isPaid()) {
            throw new ServiceException(OrderErrorCode.ORDER_RETURN_NOT_PAID);
        }
        if (orderItemIds == null || orderItemIds.isEmpty() || Set.copyOf(orderItemIds).size() != orderItemIds.size()) {
            throw new ServiceException(OrderErrorCode.ORDER_RETURN_ITEM_INVALID);
        }
        List<OrderItem> targets = items.stream()
                .filter(item -> orderItemIds.contains(item.getId()) && !item.isGone() && item.getSellerId() != null)
                .toList();
        if (targets.size() != orderItemIds.size()) {
            throw new ServiceException(OrderErrorCode.ORDER_RETURN_ITEM_INVALID);
        }
        return targets;
    }

    private record CancelCalc(long refund, boolean orderCanceled, long remainingDiscount) {
    }

    // 이번에 뺄 항목 전체의 환불 금액. canceledBefore는 이번 취소 전에 이미 취소된 항목인지.
    // 쿠폰 할인은 결제 때 줄마다 고정했으므로 누가 취소하든 환불은 "줄 금액 - 그 줄의 할인"의 합이다.
    // 남은 상품의 할인은 그대로라 구매자가 더 내야 하는 경우가 없고, 지금까지 낸 돈보다 많이 돌려주지 않는다.
    // 남는 항목이 없으면 낸 돈을 모두 돌려준다.
    private CancelCalc calculate(List<OrderItem> targets, Predicate<OrderItem> canceledBefore, long netPaid) {
        List<OrderItem> remaining = items.stream()
                .filter(item -> !canceledBefore.test(item) && !targets.contains(item))
                .toList();
        if (remaining.isEmpty()) {
            return new CancelCalc(netPaid, true, 0);
        }
        long refund = targets.stream().mapToLong(item -> item.amount() - discountOf(item)).sum();
        long remainingDiscount = remaining.stream().mapToLong(this::discountOf).sum();
        return new CancelCalc(Math.min(refund, netPaid), false, remainingDiscount);
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
        return cancelItems(sellerId, null, reason, now).refundAmount();
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
                .filter(item -> orderItemIds.contains(item.getId()) && !item.isGone() && item.getSellerId() != null)
                .toList();
        if (targets.size() != orderItemIds.size()) {
            throw new ServiceException(OrderErrorCode.ORDER_CANCEL_ITEM_INVALID);
        }
        return targets;
    }

    // 판매글 내림 요청에 싣는 그 판매자의 아직 취소되지 않은 항목
    public List<Long> uncanceledItemIdsOf(Long sellerId) {
        return items.stream()
                .filter(item -> sellerId.equals(item.getSellerId()) && !item.isGone())
                .map(OrderItem::getId)
                .toList();
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
