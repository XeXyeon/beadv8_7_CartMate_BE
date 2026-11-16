package com.cartmate.cartmate.boundedContext.orderService.order.app.buyercancel;

import com.cartmate.cartmate.boundedContext.orderService.order.app.personal.BuyerCancelStart;
import com.cartmate.cartmate.boundedContext.orderService.order.app.personal.OrderFacade;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.buyercancel.BuyerCancelRequest;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.buyercancel.BuyerCancelRequestStatus;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.BuyerCancelResult;
import com.cartmate.cartmate.boundedContext.orderService.order.exception.OrderErrorCode;
import com.cartmate.cartmate.boundedContext.orderService.order.out.buyercancel.BuyerCancelRequestRepository;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 구매자 취소: 고른 상품의 취소를 배송 서버에 먼저 요청하고, 판매자별 결과를 받은 뒤에만 그 판매자 몫을 취소·환불한다.
 * 결제 대기 주문은 배송이 없으므로 바로 주문 전체를 취소한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuyerCancelFacade {

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
