package com.cartmate.cartmate.boundedContext.orderService.order.app.buyercancel;

import com.cartmate.cartmate.boundedContext.orderService.order.app.personal.BuyerCancelStart;
import com.cartmate.cartmate.boundedContext.orderService.order.app.personal.OrderFacade;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.buyercancel.BuyerCancelRequest;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.buyercancel.BuyerCancelRequestStatus;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.BuyerCancelResult;
import com.cartmate.cartmate.boundedContext.orderService.order.exception.OrderErrorCode;
import com.cartmate.cartmate.boundedContext.orderService.order.out.buyercancel.BuyerCancelRequestRepository;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.shared.delivery.event.DeliveryBuyerCancelResultEvent;
import com.cartmate.cartmate.shared.delivery.event.DeliveryBuyerCancelResultEvent.SellerResult;
import com.cartmate.cartmate.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 구매자 취소: 고른 상품의 취소를 배송 서버에 먼저 요청하고, 판매자별 결과를 받은 뒤에만 그 판매자 몫을 취소·환불한다.
 * 결제 대기 주문은 배송이 없으므로 바로 주문 전체를 취소한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuyerCancelFacade {

    static final String RESULT_MISSING = "RESULT_MISSING";

    private final BuyerCancelRequestRepository requestRepository;
    private final OrderFacade orderFacade;
    private final DomainEventPublisher eventPublisher;
    private final Clock clock;

    // 주문 행을 잠근 채로 처리 중인 요청을 확인해, 같은 주문의 동시 요청이 둘 다 들어가지 않게 한다
    @Transactional
    public BuyerCancelResult request(Long memberId, Long orderId, List<Long> orderItemIds) {
        BuyerCancelStart start = orderFacade.startBuyerCancel(memberId, orderId, orderItemIds);
        if (start.canceledNow()) {
            return BuyerCancelResult.CANCELED;
        }
        if (requestRepository.existsByOrderIdAndStatus(orderId, BuyerCancelRequestStatus.REQUESTED)) {
            throw new ServiceException(OrderErrorCode.ORDER_CANCEL_IN_PROGRESS);
        }
        BuyerCancelRequest request = requestRepository.save(
                BuyerCancelRequest.create(orderId, memberId, start.targets(), LocalDateTime.now(clock)));
        eventPublisher.publish(request.toEvent());
        return BuyerCancelResult.REQUESTED;
    }

    // 판매자마다 따로 취소를 확정한다. 한 판매자가 거절해도 다른 판매자 몫은 취소·환불한다.
    @Transactional
    public void applyResult(DeliveryBuyerCancelResultEvent event) {
        Optional<BuyerCancelRequest> found = requestRepository.findByIdForUpdate(event.requestId());
        if (found.isEmpty() || !found.get().isRequested() || !found.get().getOrderId().equals(event.orderId())) {
            log.info("반영하지 않는 구매자 취소 결과: requestId={}, orderId={}, status={}", event.requestId(), event.orderId(),
                    found.map(BuyerCancelRequest::getStatus).orElse(null));
            return;
        }
        BuyerCancelRequest request = found.get();
        Map<Long, SellerResult> bySeller = event.results().stream()
                .collect(Collectors.toMap(SellerResult::sellerId, Function.identity(), (first, second) -> first));
        Map<Long, List<Long>> toCancel = new LinkedHashMap<>();
        for (Long sellerId : request.sellerIds()) {
            SellerResult result = bySeller.get(sellerId);
            if (result == null) {
                log.warn("구매자 취소 결과에 판매자가 빠짐: requestId={}, sellerId={}", request.getId(), sellerId);
                request.rejectSeller(sellerId, RESULT_MISSING);
            } else if (result.canceled()) {
                toCancel.put(sellerId, request.orderItemIdsOf(sellerId));
                request.markSellerCanceled(sellerId);
            } else {
                request.rejectSeller(sellerId, result.failureCode());
            }
        }
        // 취소된 판매자들을 한 번에 계산해야 순서와 상관없이 환불 금액이 같다
        if (!toCancel.isEmpty()) {
            orderFacade.cancelItemsByBuyer(request.getOrderId(), toCancel);
        }
        request.complete();
    }

    @Transactional(readOnly = true)
    public List<Long> findIdsToResend(LocalDateTime lastSentBefore) {
        return requestRepository.findIdsByStatusAndLastSentAtBefore(BuyerCancelRequestStatus.REQUESTED, lastSentBefore);
    }

    @Transactional
    public void resend(Long requestId, Duration resendAfter) {
        requestRepository.findByIdForUpdate(requestId)
                .filter(request -> request.resend(LocalDateTime.now(clock), resendAfter))
                .ifPresent(request -> {
                    log.warn("배송 서버 응답이 없어 구매자 취소 요청 재발행: requestId={}, orderId={}, firstRequestedAt={}",
                            request.getId(), request.getOrderId(), request.getRequestedAt());
                    eventPublisher.publish(request.toEvent());
                });
    }
}
