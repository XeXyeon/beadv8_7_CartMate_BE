package com.cartmate.cartmate.boundedContext.orderService.order.app.groupshared;

import com.cartmate.cartmate.boundedContext.orderService.order.app.OrderPaymentRetryService;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.groupshared.SharedGroupOrder;
import com.cartmate.cartmate.boundedContext.orderService.order.exception.OrderErrorCode;
import com.cartmate.cartmate.boundedContext.orderService.order.out.FakeOrderInventoryClient;
import com.cartmate.cartmate.boundedContext.orderService.order.out.FakeOrderPaymentClient;
import com.cartmate.cartmate.boundedContext.orderService.order.out.groupshared.SharedGroupOrderRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.shared.order.OrderType;
import com.cartmate.cartmate.shared.order.SharedGroupOrderCancelReason;
import com.cartmate.cartmate.shared.order.SharedGroupOrderStatus;
import com.cartmate.cartmate.shared.order.dto.TeamDeliveryAddress;
import com.cartmate.cartmate.support.CartMateIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static com.cartmate.cartmate.boundedContext.orderService.order.support.OrderFixtures.sharedLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 공동 주문의 재고 선점: 첫 분담금 결제 직전에 상품 서버 재고 선점(주문 종류 SHARED_GROUP)을 한 번 하고 "선점함"으로 표시한다.
 * 선점 단계는 결제와 다른 트랜잭션으로 먼저 커밋해, 결제가 실패해도 주문 서버와 상품 서버가 둘 다 "선점함"으로 남는다.
 * 재고가 부족하면 차감하지 않고 공동 주문을 재고 부족으로 취소한다.
 */
@CartMateIntegrationTest
class SharedGroupOrderStockReservationTest {

    private static final TeamDeliveryAddress ADDRESS =
            new TeamDeliveryAddress("홍길동", "010-1234-5678", "12345", "서울시 강남구", "101호");

    @Autowired
    private OrderPaymentRetryService orderPaymentRetryService;
    @Autowired
    private SharedGroupOrderRepository repository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private FakeOrderInventoryClient inventoryClient;
    @Autowired
    private FakeOrderPaymentClient paymentClient;

    private final long base = 9_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L) * 100;
    private long sequence;

    private long creator;
    private long member;
    private Long orderId;

    @BeforeEach
    void setUp() {
        inventoryClient.reset();
        paymentClient.clearChargeFailure();
        creator = nextId();
        member = nextId();
        orderId = saveOrder(creator, List.of(creator, member));
    }

    @AfterEach
    void tearDown() {
        inventoryClient.reset();
        paymentClient.clearChargeFailure();
    }

    @Test
    @DisplayName("첫 결제 때 공동 주문 종류(SHARED_GROUP)로 한 번 선점하고, 다음 참여자 결제에서는 다시 선점하지 않는다")
    void reservesOnceOnFirstPayment() {
        orderPaymentRetryService.paySharedGroupOrder(creator, orderId, ADDRESS);

        assertThat(inventoryClient.reservedOrderIds()).containsExactly(orderId);
        assertThat(inventoryClient.reservedOrderTypes()).containsExactly(OrderType.SHARED_GROUP);
        assertThat(findOrder().isStockReserved()).isTrue();

        orderPaymentRetryService.paySharedGroupOrder(member, orderId, null);

        assertThat(inventoryClient.reservedOrderIds()).containsExactly(orderId);
        assertThat(findOrder().getStatus()).isEqualTo(SharedGroupOrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("첫 참여자의 결제가 실패해도 선점과 \"선점함\" 표시는 남고, 다음 결제에서는 다시 선점하지 않는다")
    void reservationSurvivesChargeFailure() {
        ServiceException insufficient = new ServiceException("400-INSUFFICIENT_BALANCE", "예치금이 부족합니다.");
        paymentClient.failChargeWith(insufficient);

        assertThatThrownBy(() -> orderPaymentRetryService.paySharedGroupOrder(creator, orderId, ADDRESS))
                .isSameAs(insufficient);

        SharedGroupOrder afterFailure = findOrder();
        assertThat(afterFailure.isStockReserved()).isTrue();
        assertThat(afterFailure.getStatus()).isEqualTo(SharedGroupOrderStatus.PAYING);
        assertThat(afterFailure.getParticipant(creator).isPaid()).isFalse();
        assertThat(inventoryClient.reservedOrderIds()).containsExactly(orderId);

        paymentClient.clearChargeFailure();
        orderPaymentRetryService.paySharedGroupOrder(member, orderId, null);

        assertThat(inventoryClient.reservedOrderIds()).containsExactly(orderId);
        assertThat(findOrder().getParticipant(member).isPaid()).isTrue();
    }

    @Test
    @DisplayName("재고가 부족하면 차감하지 않고 공동 주문을 재고 부족으로 취소한다")
    void outOfStockCancelsOrder() {
        inventoryClient.failReserveWith(new ServiceException(OrderErrorCode.ORDER_OUT_OF_STOCK));

        assertThatThrownBy(() -> orderPaymentRetryService.paySharedGroupOrder(creator, orderId, ADDRESS))
                .isInstanceOf(ServiceException.class)
                .extracting("resultCode")
                .isEqualTo(OrderErrorCode.ORDER_OUT_OF_STOCK.resultCode());

        SharedGroupOrder order = findOrder();
        assertThat(order.getStatus()).isEqualTo(SharedGroupOrderStatus.CANCELED);
        assertThat(order.getCancelReason()).isEqualTo(SharedGroupOrderCancelReason.OUT_OF_STOCK);
        assertThat(order.isStockReserved()).isFalse();
        assertThat(order.getParticipant(creator).isPaid()).isFalse();
    }

    @Test
    @DisplayName("결제할 수 없는 상황(기한 지남)이면 선점하지 않는다")
    void noReservationWhenNotPayable() {
        Long expiredOrderId = saveOrder(creator, List.of(creator, member),
                LocalDateTime.now().minus(SharedGroupOrder.PAYMENT_PERIOD).minusMinutes(5));

        assertThatThrownBy(() -> orderPaymentRetryService.paySharedGroupOrder(creator, expiredOrderId, ADDRESS))
                .isInstanceOf(ServiceException.class)
                .extracting("resultCode")
                .isEqualTo(OrderErrorCode.ORDER_NOT_PAYABLE.resultCode());

        assertThat(inventoryClient.reservedOrderIds()).isEmpty();
    }

    private long nextId() {
        return base + sequence++;
    }

    private Long saveOrder(Long creatorId, List<Long> participants) {
        return saveOrder(creatorId, participants, LocalDateTime.now());
    }

    private Long saveOrder(Long creatorId, List<Long> participants, LocalDateTime closedAt) {
        return transactionTemplate.execute(status -> repository.save(SharedGroupOrder.create(nextId(), creatorId,
                participants, List.of(sharedLine(2L, 4L, 1L, 1, 30000L)), closedAt)).getId());
    }

    private SharedGroupOrder findOrder() {
        return transactionTemplate.execute(status -> {
            SharedGroupOrder order = repository.findById(orderId).orElseThrow();
            order.getParticipants().size();
            return order;
        });
    }
}
