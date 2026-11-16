package com.cartmate.cartmate.boundedContext.etcService.delivery.domain;

import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import com.cartmate.cartmate.boundedContext.etcService.delivery.exception.DeliveryErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(name = "delivery")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    // 배송 생성 시점의 주문번호 스냅샷. 주문번호가 없는 주문(공유 공동구매)과 이전에 만든 배송은 null이다.
    @Column(name = "order_number")
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "purchase_type", nullable = false)
    private PurchaseType purchaseType;

    @Column(name = "carrier")
    private String carrier;

    @Column(name = "tracking_number")
    private String trackingNumber;

    @Column(name = "receiver_name", nullable = false)
    private String receiverName;

    @Column(name = "receiver_phone", nullable = false)
    private String receiverPhone;

    @Column(name = "zip_code", nullable = false)
    private String zipCode;

    @Column(name = "address", nullable = false)
    private String address;

    @Column(name = "address_detail", nullable = false)
    private String addressDetail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DeliveryStatus status;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;     //요청 내용 SHA-256 해시 (멱등키 재요청 비교용)

    @Column(name = "prepared_at")
    private LocalDateTime preparedAt;

    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;    //배송출발시간

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;  //배송 완료시간

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;  //구매확정 시간

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    // 왜 취소됐는지. 이 칸이 생기기 전에 취소된 배송은 null이다.
    // 칸 종류를 문자열로 정해 둔다. 정하지 않으면 MySQL enum 칸이 만들어져 사유를 추가할 때마다 칸을 바꿔야 한다.
    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason", columnDefinition = "varchar(30)")
    private DeliveryCancelReason cancelReason;

    // 분산락이 뚫리거나 행 락을 쓰는 이벤트 경로(취소 판정)와 겹칠 때 늦게 커밋한 쪽을 실패시킨다.
    // 이미 있는 행은 컬럼이 추가될 때 0으로 채워진다(기본값). null이면 버전 비교가 맞지 않아 수정이 모두 실패한다.
    @Version
    @Column(name = "version", nullable = false, columnDefinition = "bigint not null default 0")
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;    //배송생성시간

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Getter(AccessLevel.NONE)
    @OneToMany(mappedBy = "delivery")
    @OrderBy("id")
    private List<DeliveryItem> items = new ArrayList<>();

    public static Delivery create(
            Long memberId, Long sellerId, Long orderId, String orderNumber, PurchaseType purchaseType,
            String receiverName, String receiverPhone,
            String zipCode, String address, String addressDetail, String idempotencyKey,
            String requestHash
    ) {
        return new Delivery(
                memberId, sellerId, orderId, orderNumber, purchaseType,
                receiverName, receiverPhone, zipCode, address, addressDetail, idempotencyKey, requestHash
        );
    }

    private Delivery(
            Long memberId, Long sellerId, Long orderId, String orderNumber, PurchaseType purchaseType,
            String receiverName, String receiverPhone,
            String zipCode, String address, String addressDetail, String idempotencyKey,
            String requestHash
    ) {
        this.memberId = memberId;
        this.sellerId = sellerId;
        this.orderId = orderId;
        this.orderNumber = orderNumber;
        this.purchaseType = purchaseType;
        this.receiverName = receiverName;
        this.receiverPhone = receiverPhone;
        this.zipCode = zipCode;
        this.address = address;
        this.addressDetail = addressDetail;
        this.status = DeliveryStatus.REQUESTED;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
    }

    public BuyerCancelVerdict buyerCancelVerdict(LocalDateTime requestedAt) {
        if (this.status == DeliveryStatus.CANCELED || this.status == DeliveryStatus.REQUESTED) {
            return BuyerCancelVerdict.ALLOWED;
        }
        if (requestedAt == null || this.preparedAt == null || !this.preparedAt.isAfter(requestedAt)) {
            return BuyerCancelVerdict.NOT_CANCELABLE;
        }
        if (this.status == DeliveryStatus.PREPARING) {
            return BuyerCancelVerdict.ALLOWED;
        }
        return BuyerCancelVerdict.SHIPPED_AFTER_REQUEST;
    }

    // 판매글 내림으로 인한 취소: 판매자가 판매를 멈춘 것이라 요청 시각과 상관없이 배송준비중(판매자 확인 후)까지 취소한다.
    // 발송(배송중) 이후면 취소할 수 없다. 이미 취소된 배송은 취소된 것으로 본다.
    public boolean isCancelableByPostHidden() {
        return this.status == DeliveryStatus.CANCELED
                || this.status == DeliveryStatus.REQUESTED
                || this.status == DeliveryStatus.PREPARING;
    }

    // 취소 시각은 밖에서 받는다. 한 번의 판매글 내림으로 여러 배송이 취소될 때 같은 시각으로 남기기 위해서다.
    public void cancelByPostHidden(LocalDateTime canceledAt) {
        if (this.status == DeliveryStatus.CANCELED) {
            return;
        }
        if (!isCancelableByPostHidden()) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_NOT_CANCELABLE);
        }

        markCanceled(DeliveryCancelReason.SALES_STOPPED, canceledAt);
    }

    // 판매자가 품절 같은 사정으로 고른 상품을 취소한다. 물건이 나가기 전(배송요청·배송준비중)까지만 된다. 전부 고르면 배송도 취소된다.
    // 송장 정보는 지우지 않는다. 송장을 발급한 뒤 취소된 배송인지 나중에 확인할 수 있게 남긴다.
    // 고른 상품이 모두 같은 사유로 이미 취소됐으면(더블클릭 등) 아무것도 바꾸지 않고 빈 목록을 돌려준다.
    // 사유는 한 번 정하면 바꾸지 않는다. 이미 주문 서버와 구매자 화면에 퍼졌기 때문이다.
    public List<DeliveryItem> cancelItemsBySeller(Set<Long> deliveryItemIds, DeliveryCancelReason reason,
                                                  LocalDateTime canceledAt) {
        if (reason == null) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_CANCEL_REASON_REQUIRED);
        }
        if (!reason.isSellerReason()) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_CANCEL_REASON_NOT_ALLOWED);
        }
        if (deliveryItemIds == null || deliveryItemIds.isEmpty()) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_CANCEL_ITEMS_REQUIRED);
        }
        List<DeliveryItem> selected = items.stream()
                .filter(item -> item.getId() != null && deliveryItemIds.contains(item.getId()))
                .toList();
        if (selected.size() != deliveryItemIds.size()) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_ITEM_NOT_FOUND);
        }
        Optional<DeliveryItem> canceledByOther = selected.stream()
                .filter(item -> item.isCanceled() && item.getCancelReason() != reason)
                .findFirst();
        if (canceledByOther.isPresent()) {
            throw alreadyCanceled(canceledByOther.get().getCancelReason());
        }
        if (selected.stream().allMatch(DeliveryItem::isCanceled)) {
            return List.of();
        }
        if (this.status != DeliveryStatus.REQUESTED && this.status != DeliveryStatus.PREPARING) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_NOT_CANCELABLE_BY_SELLER);
        }
        Set<Long> orderItemIds = selected.stream().map(DeliveryItem::getOrderItemId).collect(Collectors.toSet());
        return cancelItems(orderItemIds, reason, canceledAt);
    }

    void addItem(DeliveryItem item) {
        items.add(item);
    }

    // 고른 주문 항목의 배송 상품을 취소하고, 남은 상품이 없으면 배송도 취소한다. 이번에 새로 취소된 상품만 돌려준다.
    // 취소할 수 있는지는 경로마다 규칙(요청 시각 판정, 판매글 내림, 판매자 사유)이 달라 부르는 쪽이 먼저 확인한다.
    // 여기서는 이미 나간 배송을 실수로 건드리지 않게만 막는다.
    public List<DeliveryItem> cancelItems(Set<Long> orderItemIds, DeliveryCancelReason reason, LocalDateTime canceledAt) {
        if (this.status == DeliveryStatus.CANCELED) {
            return List.of();
        }
        if (this.status != DeliveryStatus.REQUESTED && this.status != DeliveryStatus.PREPARING) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_NOT_CANCELABLE);
        }
        List<DeliveryItem> newlyCanceled = new ArrayList<>();
        for (DeliveryItem item : items) {
            if (orderItemIds.contains(item.getOrderItemId()) && item.cancel(reason, canceledAt)) {
                newlyCanceled.add(item);
            }
        }
        if (!newlyCanceled.isEmpty() && items.stream().allMatch(DeliveryItem::isCanceled)) {
            markCanceled(reason, canceledAt);
        }
        return newlyCanceled;
    }

    // 배송을 취소하면 남은 상품도 같은 사유로 취소 표시한다. 클레임·정산이 상품의 취소 표시만 보고 뺄 수 있게 하려는 것이다.
    private void markCanceled(DeliveryCancelReason reason, LocalDateTime canceledAt) {
        this.status = DeliveryStatus.CANCELED;
        this.canceledAt = canceledAt;
        this.cancelReason = reason;
        items.forEach(item -> item.cancel(reason, canceledAt));
    }

    // 이미 취소된 배송이나 상품을 다시 다루려 하면 왜 취소됐는지 알려 준다. 사유가 없는 예전 취소는 문구만 보낸다.
    private ServiceException alreadyCanceled(DeliveryCancelReason reason) {
        DeliveryErrorCode code = DeliveryErrorCode.DELIVERY_ALREADY_CANCELED;
        if (reason == null) {
            return new ServiceException(code);
        }
        return new ServiceException(code.resultCode(),
                code.getMsg() + " (사유: " + reason.getLabel() + ")");
    }

    // 택배사와 송장번호를 등록하고 배송요청을 배송준비로 바꾼다.
    // 이미 같은 송장 정보로 준비 처리된 경우에는 아무것도 하지 않아 재요청해도 안전하다.
    public void prepare(String carrier, String trackingNumber) {
        if (this.status == DeliveryStatus.CANCELED) {
            throw alreadyCanceled(this.cancelReason);
        }
        if (carrier == null || carrier.isBlank()) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_CARRIER_REQUIRED);
        }
        if (trackingNumber == null || trackingNumber.isBlank()) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_TRACKING_NUMBER_REQUIRED);
        }
        String newCarrier = carrier.trim();
        String newTrackingNumber = trackingNumber.trim();

        if (this.status == DeliveryStatus.PREPARING) {
            if (newCarrier.equals(this.carrier) && newTrackingNumber.equals(this.trackingNumber)) {
                return;
            }
            throw new ServiceException(DeliveryErrorCode.DELIVERY_ALREADY_PREPARED);
        }
        if (this.status != DeliveryStatus.REQUESTED) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_INVALID_STATUS);
        }

        this.carrier = newCarrier;
        this.trackingNumber = newTrackingNumber;
        this.status = DeliveryStatus.PREPARING;
        this.preparedAt = LocalDateTime.now();
    }

    // 배송준비를 배송중으로 바꾸고 출발 시각을 기록한다. 이미 배송중이면 아무것도 하지 않아 재요청해도 안전하다.
    public void ship() {
        if (this.status == DeliveryStatus.CANCELED) {
            throw alreadyCanceled(this.cancelReason);
        }
        if (this.status == DeliveryStatus.SHIPPING) {
            return;
        }
        if (this.status != DeliveryStatus.PREPARING) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_INVALID_STATUS);
        }

        this.status = DeliveryStatus.SHIPPING;
        this.shippedAt = LocalDateTime.now();
    }

    // 배송중을 배송완료로 바꾸고 완료 시각을 기록한다. 이미 배송완료면 아무것도 하지 않아 재실행해도 안전하다.
    public void deliver() {
        if (this.status == DeliveryStatus.DELIVERED) {
            return;
        }
        if (this.status != DeliveryStatus.SHIPPING) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_INVALID_STATUS);
        }

        this.status = DeliveryStatus.DELIVERED;
        this.deliveredAt = LocalDateTime.now();
    }

    // 배송완료를 구매확정으로 바꾸고 확정 시각을 기록한다. 이미 구매확정이면 아무것도 하지 않아 재요청해도 안전하다.
    // 상태를 실제로 바꿨으면 true, 이미 구매확정이라 바꾸지 않았으면 false를 반환한다.
    public boolean confirm() {
        if (this.status == DeliveryStatus.CONFIRMED) {
            return false;
        }
        if (this.status != DeliveryStatus.DELIVERED) {
            throw new ServiceException(DeliveryErrorCode.DELIVERY_INVALID_STATUS);
        }

        this.status = DeliveryStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
        return true;
    }
}
