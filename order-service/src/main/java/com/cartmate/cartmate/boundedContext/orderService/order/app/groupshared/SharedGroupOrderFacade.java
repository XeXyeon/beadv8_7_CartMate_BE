package com.cartmate.cartmate.boundedContext.orderService.order.app.groupshared;

import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import com.cartmate.cartmate.shared.order.SharedGroupOrderStatus;
import com.cartmate.cartmate.boundedContext.orderService.order.dto.groupshared.SharedGroupOrderDetailResponse;
import com.cartmate.cartmate.boundedContext.orderService.order.dto.groupshared.SharedGroupOrderSummaryResponse;
import com.cartmate.cartmate.shared.cart.event.SharedGroupPurchaseClosedEvent;
import com.cartmate.cartmate.shared.order.dto.SharedGroupOrderPaymentInfo;
import com.cartmate.cartmate.shared.order.dto.TeamDeliveryAddress;
import com.cartmate.cartmate.shared.payment.dto.PaymentResult;
import com.cartmate.cartmate.boundedContext.orderService.order.exception.OrderErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.http.InternalApiClient.InternalServerUnavailableException;
import com.cartmate.cartmate.shared.coupon.dto.CouponOption;
import com.cartmate.cartmate.shared.coupon.dto.CouponOrderTarget;
import com.cartmate.cartmate.shared.coupon.dto.CouponQuote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SharedGroupOrderFacade {

    private final SharedGroupOrderCreateUseCase sharedGroupOrderCreateUseCase;
    private final SharedGroupOrderQueryUseCase sharedGroupOrderQueryUseCase;
    private final SharedGroupOrderPaymentUseCase sharedGroupOrderPaymentUseCase;
    private final SharedGroupOrderCancelUseCase sharedGroupOrderCancelUseCase;
    private final Clock clock;
    // 분담금 결제의 짧은 트랜잭션들(쿠폰 기록 / 결제 / 쿠폰 복구 요청). pay()는 트랜잭션 없이 불려 각각 새 트랜잭션이 된다
    private final TransactionOperations transaction;

    @Transactional
    public void createFromClosedPurchase(SharedGroupPurchaseClosedEvent event) {
        sharedGroupOrderCreateUseCase.createFromClosedPurchase(event, LocalDateTime.now(clock));
    }

    @Transactional
    public void reportCreationFailed(Long sharedGroupPurchaseId, String reason) {
        sharedGroupOrderCreateUseCase.reportCreationFailed(sharedGroupPurchaseId, reason);
    }

    @Transactional(readOnly = true)
    public SharedGroupOrderPaymentInfo getPaymentInfo(Long sharedGroupOrderId, Long memberId, LocalDateTime now) {
        return sharedGroupOrderPaymentUseCase.getPaymentInfo(sharedGroupOrderId, memberId, now);
    }

    public PaymentResult pay(Long memberId, Long sharedGroupOrderId, TeamDeliveryAddress teamDeliveryAddress) {
        return pay(memberId, sharedGroupOrderId, teamDeliveryAddress, null);
    }

    /**
     * 분담금 결제. 쿠폰이 있으면 결제 트랜잭션 전에 [트랜잭션] 쓸 쿠폰 기록 → 쿠폰 사용(상품 서버)을 끝내고, 그 할인만큼 덜 차감한다.
     * 결제가 실패로 끝나면(업무 거절) [트랜잭션] 기록을 지우고 쿠폰 복구를 요청한다 — 결제 트랜잭션은 롤백되므로 복구 요청은 따로 커밋한다.
     * 결과를 모를 때(503)와 팀 배송지만 빠진 경우(차감이 남아 다시 결제할 때 반영)는 기록을 둔다: 재시도가 같은 쿠폰으로 반영하거나,
     * 결제가 끝나지 못한 채 공동 주문이 취소되면 그때 돌려준다.
     */
    public PaymentResult pay(Long memberId, Long sharedGroupOrderId, TeamDeliveryAddress teamDeliveryAddress,
                             Long memberCouponId) {
        LocalDateTime now = LocalDateTime.now(clock);
        // 요청에 쿠폰이 없어도 결과를 모르는 앞 시도가 기록한 쿠폰이 있으면 그 쿠폰으로 이어서 한다(차감액이 그 할인으로 정해졌을 수 있다)
        Long couponId = memberCouponId != null ? memberCouponId
                : transaction.execute(status -> sharedGroupOrderPaymentUseCase.recordedCoupon(sharedGroupOrderId, memberId));
        CouponQuote usedCoupon = null;
        if (couponId != null) {
            CouponOrderTarget target = transaction.execute(status -> {
                sharedGroupOrderPaymentUseCase.recordCoupon(sharedGroupOrderId, memberId, couponId);
                return sharedGroupOrderPaymentUseCase.couponTarget(sharedGroupOrderId, memberId);
            });
            try {
                usedCoupon = sharedGroupOrderPaymentUseCase.useCoupon(sharedGroupOrderId, memberId, couponId, target);
            } catch (RuntimeException e) {
                // 쿠폰을 못 썼거나 썼는지 모른다. 아직 차감 전이라 복구를 요청한다(쓰지 않았으면 복구는 아무 일도 하지 않는다)
                releaseCoupon(sharedGroupOrderId, memberId);
                throw e;
            }
        }
        CouponQuote applied = usedCoupon;
        try {
            return transaction.execute(status ->
                    sharedGroupOrderPaymentUseCase.pay(sharedGroupOrderId, memberId, teamDeliveryAddress, applied, now));
        } catch (InternalServerUnavailableException e) {
            throw e;
        } catch (ServiceException e) {
            if (couponId != null && !OrderErrorCode.ORDER_TEAM_ADDRESS_REQUIRED.resultCode().equals(e.getResultCode())) {
                releaseCoupon(sharedGroupOrderId, memberId);
            }
            throw e;
        }
    }

    // 복구 요청이 실패해도 원래 오류를 가리지 않는다(기록이 남아 공동 주문 취소 때 돌려준다)
    private void releaseCoupon(Long sharedGroupOrderId, Long memberId) {
        try {
            transaction.executeWithoutResult(status ->
                    sharedGroupOrderPaymentUseCase.releaseCoupon(sharedGroupOrderId, memberId));
        } catch (RuntimeException e) {
            log.warn("분담금 결제 실패 후 쿠폰 복구 요청 실패 — 공동 주문 취소 때 돌려준다: 공동 주문 #{} 회원 #{}",
                    sharedGroupOrderId, memberId, e);
        }
    }

    @Transactional(readOnly = true)
    public List<CouponOption> couponOptions(Long memberId, Long sharedGroupOrderId) {
        return sharedGroupOrderPaymentUseCase.couponOptions(sharedGroupOrderId, memberId);
    }

    @Transactional
    public void markStockReserved(Long sharedGroupOrderId) {
        sharedGroupOrderPaymentUseCase.markStockReserved(sharedGroupOrderId);
    }

    @Transactional
    public void applyPayment(Long sharedGroupOrderId, Long memberId, Long paymentId, LocalDateTime paidAt,
                             TeamDeliveryAddress teamDeliveryAddress) {
        sharedGroupOrderPaymentUseCase.applyPayment(sharedGroupOrderId, memberId, paymentId, paidAt, teamDeliveryAddress);
    }

    @Transactional
    public void cancelByCreator(Long memberId, Long sharedGroupOrderId) {
        sharedGroupOrderCancelUseCase.cancelByCreator(memberId, sharedGroupOrderId);
    }

    @Transactional
    public void cancelByDeadline(Long sharedGroupOrderId) {
        sharedGroupOrderCancelUseCase.cancelByDeadline(sharedGroupOrderId);
    }

    @Transactional
    public void cancelByOutOfStock(Long sharedGroupOrderId) {
        sharedGroupOrderCancelUseCase.cancelByOutOfStock(sharedGroupOrderId);
    }

    @Transactional(readOnly = true)
    public List<Long> findIdsToCancelByDeadline(LocalDateTime now) {
        return sharedGroupOrderCancelUseCase.findIdsToCancelByDeadline(now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelBySalesStopped(Long sharedGroupOrderId) {
        sharedGroupOrderCancelUseCase.cancelBySalesStopped(sharedGroupOrderId);
    }

    @Transactional(readOnly = true)
    public List<Long> findIdsByStatusAndProductIds(SharedGroupOrderStatus status, List<Long> productIds) {
        return sharedGroupOrderCancelUseCase.findIdsByStatusAndProductIds(status, productIds);
    }

    // 배송 서버의 판매글 내림 취소 결과를 반영하는 트랜잭션에 참여한다 (요청 장부와 함께 커밋).
    @Transactional
    public void cancelWholeBySalesStopResult(Long sharedGroupOrderId) {
        sharedGroupOrderCancelUseCase.cancelBySalesStopped(sharedGroupOrderId);
    }

    @Transactional(readOnly = true)
    public List<Long> findUncanceledItemIds(Long sharedGroupOrderId, Long sellerId) {
        return sharedGroupOrderCancelUseCase.findUncanceledItemIds(sharedGroupOrderId, sellerId);
    }

    // 판매글 내림 취소 결과나 판매자 사유 취소를 반영하는 트랜잭션에 참여한다 (Inbox·요청 장부와 함께 커밋).
    @Transactional
    public Optional<Long> cancelItems(Long sharedGroupOrderId, Long sellerId, Collection<Long> orderItemIds,
                                      DeliveryCancelReason reason) {
        return sharedGroupOrderCancelUseCase.cancelItems(sharedGroupOrderId, sellerId, orderItemIds, reason);
    }

    @Transactional(readOnly = true)
    public List<SharedGroupOrderSummaryResponse> getMyList(Long memberId) {
        return sharedGroupOrderQueryUseCase.getMyList(memberId);
    }

    @Transactional(readOnly = true)
    public SharedGroupOrderDetailResponse getDetail(Long memberId, Long sharedGroupOrderId) {
        return sharedGroupOrderQueryUseCase.getDetail(memberId, sharedGroupOrderId);
    }

    @Transactional(readOnly = true)
    public SharedGroupOrderDetailResponse getDetailByPurchase(Long memberId, Long sharedGroupPurchaseId) {
        return sharedGroupOrderQueryUseCase.getDetailByPurchase(memberId, sharedGroupPurchaseId);
    }

    @Transactional(readOnly = true)
    public List<Long> findPayingIdsOfParticipant(Long memberId) {
        return sharedGroupOrderQueryUseCase.findIdsOfParticipant(memberId, List.of(SharedGroupOrderStatus.PAYING));
    }

    @Transactional(readOnly = true)
    public List<Long> findConfirmedIdsOfParticipant(Long memberId) {
        return sharedGroupOrderQueryUseCase.findIdsOfParticipant(memberId, List.of(SharedGroupOrderStatus.CONFIRMED));
    }
}
