package com.cartmate.cartmate.boundedContext.orderService.order.domain.buyercancel;

import com.cartmate.cartmate.shared.order.event.OrderBuyerCancelRequestedEvent;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 구매자가 고른 상품의 취소를 배송 서버에 요청하고 결과를 기다리는 요청 한 건.
 * 같은 주문에 여러 번(다른 상품을, 또는 거절된 뒤 다시) 요청할 수 있어 요청마다 한 줄을 둔다.
 * 결과가 오기 전까지 주문 항목은 바꾸지 않는다. 늦게 온 결과나 중복 결과는 요청이 REQUESTED가 아니면 반영하지 않는다.
 */
@Entity
@Table(name = "buyer_cancel_request",
        indexes = @Index(name = "idx_buyer_cancel_request_order", columnList = "order_id, status"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BuyerCancelRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "buyer_member_id", nullable = false)
    private Long buyerMemberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "varchar(20)")
    private BuyerCancelRequestStatus status;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "last_sent_at", nullable = false)
    private LocalDateTime lastSentAt;

    @OrderBy("id")
    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BuyerCancelRequestItem> items = new ArrayList<>();

    private BuyerCancelRequest(Long orderId, Long buyerMemberId, List<Target> targets, LocalDateTime now) {
        this.orderId = orderId;
        this.buyerMemberId = buyerMemberId;
        this.status = BuyerCancelRequestStatus.REQUESTED;
        this.requestedAt = now;
        this.lastSentAt = now;
        targets.forEach(target -> items.add(BuyerCancelRequestItem.create(this, target.orderItemId(), target.sellerId())));
    }

    public static BuyerCancelRequest create(Long orderId, Long buyerMemberId, List<Target> targets, LocalDateTime now) {
        return new BuyerCancelRequest(orderId, buyerMemberId, targets, now);
    }

    public boolean isRequested() {
        return status == BuyerCancelRequestStatus.REQUESTED;
    }

    public boolean resend(LocalDateTime now, Duration resendAfter) {
        if (!isRequested() || lastSentAt.plus(resendAfter).isAfter(now)) {
            return false;
        }
        this.lastSentAt = now;
        return true;
    }

    public List<Long> sellerIds() {
        return items.stream().map(BuyerCancelRequestItem::getSellerId).distinct().toList();
    }

    public List<Long> orderItemIdsOf(Long sellerId) {
        return itemsOf(sellerId).stream().map(BuyerCancelRequestItem::getOrderItemId).toList();
    }

    public void markSellerCanceled(Long sellerId) {
        itemsOf(sellerId).forEach(BuyerCancelRequestItem::markCanceled);
    }

    public void rejectSeller(Long sellerId, String failureCode) {
        itemsOf(sellerId).forEach(item -> item.reject(failureCode));
    }

    public void complete() {
        this.status = BuyerCancelRequestStatus.DONE;
    }

    private List<BuyerCancelRequestItem> itemsOf(Long sellerId) {
        return items.stream().filter(item -> item.getSellerId().equals(sellerId)).toList();
    }

    // 재발행해도 처음 요청 시각과 같은 항목을 싣는다. 배송 서버는 요청 시각 기준으로 판정한다
    public OrderBuyerCancelRequestedEvent toEvent() {
        return new OrderBuyerCancelRequestedEvent(id, orderId, buyerMemberId, requestedAt, items.stream()
                .map(item -> new OrderBuyerCancelRequestedEvent.Item(item.getOrderItemId(), item.getSellerId()))
                .toList());
    }

    public record Target(Long orderItemId, Long sellerId) {
    }
}
