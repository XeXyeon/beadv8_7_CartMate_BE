package com.cartmate.cartmate.boundedContext.orderService.order.domain.groupshared;

import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import com.cartmate.cartmate.shared.order.SharedGroupOrderCancelReason;
import com.cartmate.cartmate.shared.order.SharedGroupOrderStatus;
import com.cartmate.cartmate.shared.order.event.SharedGroupOrderCanceledEvent;
import com.cartmate.cartmate.shared.order.event.SharedGroupOrderCanceledEvent.SellerAmount;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.DeliveryAddress;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.boundedContext.orderService.order.exception.OrderErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Getter
@Entity
@Table(name = "shared_group_order")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SharedGroupOrder {

    public static final Duration PAYMENT_PERIOD = Duration.ofHours(48);
    public static final Duration GRACE_PERIOD = Duration.ofMinutes(10);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long sharedGroupPurchaseId;

    @Column(nullable = false)
    private Long creatorMemberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    private SharedGroupOrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(30)")
    private SharedGroupOrderCancelReason cancelReason;

    @Column(nullable = false)
    private long totalAmount;

    @Column(nullable = false)
    private LocalDateTime paymentDeadlineAt;

    @Column(nullable = false)
    private boolean stockReserved;

    @Embedded
    private DeliveryAddress teamDeliveryAddress;

    private LocalDateTime confirmedAt;

    private LocalDateTime canceledAt;

    @OrderBy("id")
    @OneToMany(mappedBy = "sharedGroupOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SharedGroupOrderParticipant> participants = new ArrayList<>();

    @OrderBy("id")
    @OneToMany(mappedBy = "sharedGroupOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SharedGroupOrderItem> items = new ArrayList<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private SharedGroupOrder(Long sharedGroupPurchaseId, Long creatorMemberId, List<Long> participantMemberIds,
                             List<OrderItemLine> itemLines, LocalDateTime closedAt) {
        if (itemLines == null || itemLines.isEmpty()) {
            throw new ServiceException(OrderErrorCode.ORDER_NO_ITEMS);
        }
        if (participantMemberIds == null || !participantMemberIds.contains(creatorMemberId)) {
            throw new ServiceException(OrderErrorCode.ORDER_CREATOR_NOT_IN_PARTICIPANTS);
        }
        if (participantMemberIds.stream().distinct().count() != participantMemberIds.size()) {
            throw new ServiceException(OrderErrorCode.ORDER_DUPLICATE_PARTICIPANTS);
        }
        this.sharedGroupPurchaseId = sharedGroupPurchaseId;
        this.creatorMemberId = creatorMemberId;
        this.status = SharedGroupOrderStatus.PAYING;
        this.paymentDeadlineAt = closedAt.plus(PAYMENT_PERIOD);
        this.stockReserved = false;

        itemLines.forEach(line -> items.add(SharedGroupOrderItem.create(this, line)));
        this.totalAmount = items.stream().mapToLong(SharedGroupOrderItem::amount).sum();

        int headCount = participantMemberIds.size();
        long perPerson = totalAmount / headCount;
        long remainder = totalAmount % headCount;
        participantMemberIds.forEach(memberId -> participants.add(SharedGroupOrderParticipant.create(
                this, memberId, memberId.equals(creatorMemberId) ? perPerson + remainder : perPerson)));
    }

    public static SharedGroupOrder create(Long sharedGroupPurchaseId, Long creatorMemberId, List<Long> participantMemberIds,
                                          List<OrderItemLine> itemLines, LocalDateTime closedAt) {
        return new SharedGroupOrder(sharedGroupPurchaseId, creatorMemberId, participantMemberIds, itemLines, closedAt);
    }

    public boolean canAcceptPayment(LocalDateTime paidAt) {
        return status == SharedGroupOrderStatus.PAYING && !paidAt.isAfter(paymentDeadlineAt);
    }

    public boolean needsStockReservation() {
        return status == SharedGroupOrderStatus.PAYING && !stockReserved;
    }

    public void markStockReserved() {
        this.stockReserved = true;
    }

    public boolean pay(Long memberId, Long paymentId, LocalDateTime paidAt, DeliveryAddress teamDeliveryAddress) {
        return pay(memberId, paymentId, paidAt, teamDeliveryAddress, null, 0, null);
    }

    // 분담금 결제를 반영한다. memberCouponId·discount는 상품 서버에서 사용 처리된 쿠폰(없으면 null·0),
    // paidAmount는 실제 차감액(없으면 확인하지 않음)으로 "분담금 - 할인"과 같아야 한다.
    // 모두 결제하면 확정하고, 참여자들의 쿠폰 할인 합을 상품 줄에 나눠 고정한다
    public boolean pay(Long memberId, Long paymentId, LocalDateTime paidAt, DeliveryAddress teamDeliveryAddress,
                       Long memberCouponId, long discount, Long paidAmount) {
        if (paymentId == null) {
            throw new ServiceException(OrderErrorCode.ORDER_PAYMENT_ID_REQUIRED);
        }
        SharedGroupOrderParticipant participant = getParticipant(memberId);
        if (participant.isPaidWith(paymentId)) {
            return false;
        }
        if (!canAcceptPayment(paidAt)) {
            throw new ServiceException(OrderErrorCode.ORDER_NOT_PAYABLE);
        }
        if (!stockReserved) {
            throw new ServiceException(OrderErrorCode.ORDER_STOCK_NOT_RESERVED);
        }
        if (isCreator(memberId)) {
            if (teamDeliveryAddress == null) {
                throw new ServiceException(OrderErrorCode.ORDER_TEAM_ADDRESS_REQUIRED);
            }
            this.teamDeliveryAddress = teamDeliveryAddress;
        }
        if (memberCouponId != null) {
            participant.applyCoupon(memberCouponId, discount);
        }
        if (paidAmount != null && paidAmount != participant.payAmount()) {
            throw new ServiceException(OrderErrorCode.ORDER_PAYMENT_AMOUNT_MISMATCH);
        }
        participant.markPaid(paymentId, paidAt);

        if (participants.stream().allMatch(SharedGroupOrderParticipant::isPaid)) {
            this.status = SharedGroupOrderStatus.CONFIRMED;
            this.confirmedAt = paidAt;
            allocateDiscount();
            return true;
        }
        return false;
    }

    // 결제 진행 때 이 참여자가 쓸 쿠폰을 기록한다. 앞선 시도가 다른 쿠폰을 기록해 뒀으면 그 쿠폰을 반환한다(복구 대상)
    public Long recordParticipantCoupon(Long memberId, Long memberCouponId) {
        validatePaying();
        return getParticipant(memberId).recordCoupon(memberCouponId);
    }

    // 결제가 실패해 이 참여자가 기록한 쿠폰을 돌려줄 때 기록을 지우고 그 쿠폰 ID를 반환한다. 이미 결제했거나 기록이 없으면 null
    public Long releaseParticipantCoupon(Long memberId) {
        return getParticipant(memberId).releaseCoupon();
    }

    // 공동 주문이 취소돼 돌려줄 참여자 쿠폰들. 한 번만 돌려주도록 표시한다(결제 안 한 참여자가 결제 도중 쓴 쿠폰 포함)
    public List<ParticipantCoupon> takeRestorableCoupons() {
        if (status != SharedGroupOrderStatus.CANCELED) {
            return List.of();
        }
        List<ParticipantCoupon> coupons = new ArrayList<>();
        participants.stream()
                .filter(SharedGroupOrderParticipant::couponRestorable)
                .forEach(participant -> {
                    coupons.add(new ParticipantCoupon(participant.getMemberId(), participant.getMemberCouponId()));
                    participant.markCouponReturned();
                });
        return coupons;
    }

    // 참여자들의 쿠폰 할인 합(모두 플랫폼 부담)을 상품 줄에 금액 비율로 나눈다. 끝전은 마지막(ID가 가장 큰) 줄에 붙인다
    private void allocateDiscount() {
        long totalDiscount = participants.stream().mapToLong(SharedGroupOrderParticipant::discountOrZero).sum();
        if (totalDiscount == 0 || totalAmount == 0) {
            return;
        }
        long given = 0;
        for (SharedGroupOrderItem item : items) {
            long share = Math.multiplyExact(totalDiscount, item.amount()) / totalAmount;
            item.assignPlatformDiscount(share);
            given += share;
        }
        SharedGroupOrderItem last = items.get(items.size() - 1);
        last.assignPlatformDiscount(last.platformDiscountOrZero() + totalDiscount - given);
    }

    public record ParticipantCoupon(Long memberId, Long memberCouponId) {
    }

    public boolean cancelByDeadline(LocalDateTime now) {
        if (status != SharedGroupOrderStatus.PAYING || now.isBefore(paymentDeadlineAt.plus(GRACE_PERIOD))) {
            return false;
        }
        cancel(SharedGroupOrderCancelReason.DEADLINE_EXPIRED, now);
        return true;
    }

    public boolean cancelByCreator(Long requesterId, LocalDateTime now) {
        validateCreator(requesterId);
        validatePaying();
        cancel(SharedGroupOrderCancelReason.CREATOR_CANCELED, now);
        return true;
    }

    public boolean cancelByOutOfStock(LocalDateTime now) {
        validatePaying();
        cancel(SharedGroupOrderCancelReason.OUT_OF_STOCK, now);
        return true;
    }

    public boolean cancelBySalesStopped(LocalDateTime now) {
        if (status == SharedGroupOrderStatus.CANCELED) {
            return false;
        }
        cancel(SharedGroupOrderCancelReason.SALES_STOPPED, now);
        return true;
    }

    // 확정된 공동 주문에서 한 판매자의 항목 중 고른 것(null이면 남은 전부)을 취소하고 그 금액(항목 금액 - 쿠폰 할인 몫)을 돌려준다.
    // 항목이 모두 취소되면 주문도 취소로 바꾼다. 이때 환불은 전체 취소 환불로 한다(마지막 판매자 몫을 따로 나누지 않음).
    public SharedItemCancelOutcome cancelItems(Long sellerId, Collection<Long> orderItemIds,
                                               DeliveryCancelReason reason, LocalDateTime now) {
        if (status != SharedGroupOrderStatus.CONFIRMED) {
            return SharedItemCancelOutcome.none();
        }
        List<SharedGroupOrderItem> targets = items.stream()
                .filter(item -> sellerId.equals(item.getSellerId()) && !item.isCanceled())
                .filter(item -> orderItemIds == null || orderItemIds.contains(item.getId()))
                .toList();
        if (targets.isEmpty()) {
            return SharedItemCancelOutcome.none();
        }
        targets.forEach(item -> item.cancel(reason, now));
        long amount = targets.stream().mapToLong(SharedGroupOrderItem::netAmount).sum();
        boolean orderCanceled = items.stream().allMatch(SharedGroupOrderItem::isCanceled);
        if (orderCanceled) {
            cancel(cancelReasonOfItems(), now);
        }
        return new SharedItemCancelOutcome(targets, amount, orderCanceled);
    }

    // 취소된 항목의 사유가 모두 같은 갈래면 그 사유, 섞였으면 여러 사유
    private SharedGroupOrderCancelReason cancelReasonOfItems() {
        Set<SharedGroupOrderCancelReason> reasons = new HashSet<>();
        items.forEach(item -> reasons.add(item.getCancelReason() == DeliveryCancelReason.SALES_STOPPED
                ? SharedGroupOrderCancelReason.SALES_STOPPED : SharedGroupOrderCancelReason.SELLER_CANCELED));
        return reasons.size() == 1 ? reasons.iterator().next() : SharedGroupOrderCancelReason.MULTIPLE_REASONS;
    }

    public long cancelSellerItems(Long sellerId, DeliveryCancelReason reason, LocalDateTime now) {
        return cancelItems(sellerId, null, reason, now).amount();
    }

    // 전체 취소보다 먼저 판매자 몫으로 따로 환불된 취소 건 목록. 한 줄이 판매자 몫 환불 이벤트 한 건이다.
    // 결제 서버가 건마다 끝전을 나눠 뺐으므로, 판매자별로 합치면 다시 나눈 금액이 실제 환불액과 1원 단위로 어긋날 수 있다.
    public List<SellerAmount> canceledBatches() {
        Map<Long, SellerAmount> byCancel = new TreeMap<>();
        items.stream()
                .filter(item -> item.isCanceled() && item.getCancelId() != null)
                .forEach(item -> byCancel.merge(item.getCancelId(),
                        new SellerAmount(item.getSellerId(), item.netAmount(), item.getCancelId()),
                        (a, b) -> new SellerAmount(a.sellerId(), a.amount() + b.amount(), a.cancelId())));
        return List.copyOf(byCancel.values());
    }

    // 먼저 판매자 몫으로 환불한 건을 결제 서버가 전체 취소 환불에서 빼도록 함께 보낸다
    public SharedGroupOrderCanceledEvent toCanceledEvent(List<SellerAmount> canceledBefore) {
        return new SharedGroupOrderCanceledEvent(
                id,
                sharedGroupPurchaseId,
                cancelReason,
                canceledAt,
                getUnpaidMemberIds(),
                creatorMemberId,
                canceledBefore
        );
    }

    // 판매글 내림 요청에 싣는 그 판매자의 아직 취소되지 않은 항목
    public List<Long> uncanceledItemIdsOf(Long sellerId) {
        return items.stream()
                .filter(item -> sellerId.equals(item.getSellerId()) && !item.isCanceled())
                .map(SharedGroupOrderItem::getId)
                .toList();
    }

    public boolean isCreator(Long memberId) {
        return creatorMemberId.equals(memberId);
    }

    public boolean isParticipant(Long memberId) {
        return participants.stream().anyMatch(participant -> participant.is(memberId));
    }

    public List<Long> getUnpaidMemberIds() {
        return participants.stream()
                .filter(participant -> !participant.isPaid())
                .map(SharedGroupOrderParticipant::getMemberId)
                .toList();
    }

    public SharedGroupOrderParticipant getParticipant(Long memberId) {
        return participants.stream()
                .filter(participant -> participant.is(memberId))
                .findFirst()
                .orElseThrow(() -> new ServiceException(OrderErrorCode.ORDER_NOT_PARTICIPANT));
    }

    private void cancel(SharedGroupOrderCancelReason reason, LocalDateTime now) {
        this.status = SharedGroupOrderStatus.CANCELED;
        this.cancelReason = reason;
        this.canceledAt = now;
    }

    private void validatePaying() {
        if (status != SharedGroupOrderStatus.PAYING) {
            throw new ServiceException(OrderErrorCode.ORDER_NOT_PAYING);
        }
    }

    private void validateCreator(Long memberId) {
        if (!isCreator(memberId)) {
            throw new ServiceException(OrderErrorCode.ORDER_CREATOR_ONLY);
        }
    }

    public record OrderItemLine(
            Long productId,
            Long inventoryId,
            Long sellerId,
            String productName,
            String optionName,
            int quantity,
            long unitPrice
    ) {
    }
}
