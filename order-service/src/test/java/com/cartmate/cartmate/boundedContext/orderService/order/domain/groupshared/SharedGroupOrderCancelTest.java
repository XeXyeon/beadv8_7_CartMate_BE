package com.cartmate.cartmate.boundedContext.orderService.order.domain.groupshared;

import static com.cartmate.cartmate.boundedContext.orderService.order.support.OrderFixtures.sharedLine;
import static com.cartmate.cartmate.boundedContext.orderService.order.support.OrderFixtures.address;
import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import com.cartmate.cartmate.shared.order.SharedGroupOrderCancelReason;
import com.cartmate.cartmate.shared.order.SharedGroupOrderStatus;
import com.cartmate.cartmate.boundedContext.orderService.order.exception.OrderErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static com.cartmate.cartmate.boundedContext.orderService.order.support.OrderFixtures.sharedOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SharedGroupOrderCancelTest {

    private static final Long CREATOR = 1L;
    private static final Long MEMBER = 2L;
    private static final LocalDateTime CLOSED_AT = LocalDateTime.of(2026, 10, 19, 12, 0);
    private static final LocalDateTime NOW = CLOSED_AT.plusHours(1);

    @Test
    @DisplayName("생성자는 결제 중인 공동 주문을 취소할 수 있다")
    void cancelByCreator() {
        SharedGroupOrder order = sharedOrder(CREATOR, MEMBER, CLOSED_AT);

        boolean canceled = order.cancelByCreator(CREATOR, NOW);

        assertThat(canceled).isTrue();
        assertThat(order.getStatus()).isEqualTo(SharedGroupOrderStatus.CANCELED);
        assertThat(order.getCancelReason()).isEqualTo(SharedGroupOrderCancelReason.CREATOR_CANCELED);
        assertThat(order.getCanceledAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("생성자가 아니면 공동 주문을 취소할 수 없다")
    void cancelByNonCreator() {
        SharedGroupOrder order = sharedOrder(CREATOR, MEMBER, CLOSED_AT);

        assertThatThrownBy(() -> order.cancelByCreator(MEMBER, NOW))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(OrderErrorCode.ORDER_CREATOR_ONLY.getMsg());
        assertThat(order.getStatus()).isEqualTo(SharedGroupOrderStatus.PAYING);
    }

    @Test
    @DisplayName("결제 중이 아닌 공동 주문은 취소할 수 없다")
    void cancelNotPaying() {
        SharedGroupOrder order = sharedOrder(CREATOR, MEMBER, CLOSED_AT);
        order.cancelByCreator(CREATOR, NOW);

        assertThatThrownBy(() -> order.cancelByCreator(CREATOR, NOW))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(OrderErrorCode.ORDER_NOT_PAYING.getMsg());
        assertThatThrownBy(() -> order.cancelByOutOfStock(NOW))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(OrderErrorCode.ORDER_NOT_PAYING.getMsg());
    }

    @Test
    @DisplayName("결제 기한에 유예 시간을 더한 시각 1초 전에는 기한 만료로 취소하지 않고, 그 시각이 되면 취소한다")
    void cancelByDeadlineGraceBoundary() {
        SharedGroupOrder order = sharedOrder(CREATOR, MEMBER, CLOSED_AT);
        LocalDateTime graceEnd = order.getPaymentDeadlineAt().plus(SharedGroupOrder.GRACE_PERIOD);

        assertThat(order.cancelByDeadline(graceEnd.minusSeconds(1))).isFalse();
        assertThat(order.getStatus()).isEqualTo(SharedGroupOrderStatus.PAYING);
        assertThat(order.getCancelReason()).isNull();

        boolean canceled = order.cancelByDeadline(graceEnd);

        assertThat(canceled).isTrue();
        assertThat(order.getStatus()).isEqualTo(SharedGroupOrderStatus.CANCELED);
        assertThat(order.getCancelReason()).isEqualTo(SharedGroupOrderCancelReason.DEADLINE_EXPIRED);
        assertThat(order.getCanceledAt()).isEqualTo(graceEnd);
    }

    @Test
    @DisplayName("이미 취소된 공동 주문은 기한 만료로 다시 취소하지 않는다")
    void cancelByDeadlineAlreadyCanceled() {
        SharedGroupOrder order = sharedOrder(CREATOR, MEMBER, CLOSED_AT);
        order.cancelByCreator(CREATOR, NOW);
        LocalDateTime graceEnd = order.getPaymentDeadlineAt().plus(SharedGroupOrder.GRACE_PERIOD);

        boolean canceled = order.cancelByDeadline(graceEnd);

        assertThat(canceled).isFalse();
        assertThat(order.getCancelReason()).isEqualTo(SharedGroupOrderCancelReason.CREATOR_CANCELED);
    }

    @Test
    @DisplayName("재고가 부족하면 결제 중인 공동 주문을 취소한다")
    void cancelByOutOfStock() {
        SharedGroupOrder order = sharedOrder(CREATOR, MEMBER, CLOSED_AT);

        boolean canceled = order.cancelByOutOfStock(NOW);

        assertThat(canceled).isTrue();
        assertThat(order.getStatus()).isEqualTo(SharedGroupOrderStatus.CANCELED);
        assertThat(order.getCancelReason()).isEqualTo(SharedGroupOrderCancelReason.OUT_OF_STOCK);
        assertThat(order.getCanceledAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("확정된 공동 주문에서 한 판매자 몫만 취소하면 그 항목만 사유와 함께 취소되고 주문은 확정으로 남는다")
    void cancelSellerItemsKeepsOrderConfirmed() {
        SharedGroupOrder order = confirmedTwoSellers();

        SharedItemCancelOutcome outcome = order.cancelItems(7L, null, DeliveryCancelReason.SELLER_OUT_OF_STOCK, NOW);
        outcome.linkCancel(31L);

        assertThat(outcome.amount()).isEqualTo(10_000L);
        assertThat(outcome.orderCanceled()).isFalse();
        assertThat(order.getStatus()).isEqualTo(SharedGroupOrderStatus.CONFIRMED);
        assertThat(order.getItems()).extracting(SharedGroupOrderItem::getCancelReason)
                .containsExactly(DeliveryCancelReason.SELLER_OUT_OF_STOCK, null);
        assertThat(order.canceledBatches()).containsExactly(
                new com.cartmate.cartmate.shared.order.event.SharedGroupOrderCanceledEvent.SellerAmount(7L, 10_000L, 31L));
    }

    @Test
    @DisplayName("마지막 판매자 몫까지 취소되면 공동 주문도 취소되고(판매 중지와 판매자 사유가 섞여 여러 사유), 다시 오면 0원")
    void cancelSellerItemsCancelsOrderWhenAllItemsCanceled() {
        SharedGroupOrder order = confirmedTwoSellers();
        order.cancelSellerItems(7L, DeliveryCancelReason.SALES_STOPPED, NOW);

        assertThat(order.cancelSellerItems(8L, DeliveryCancelReason.SELLER_PRODUCT_ISSUE, NOW.plusMinutes(1)))
                .isEqualTo(10_000L);

        assertThat(order.getStatus()).isEqualTo(SharedGroupOrderStatus.CANCELED);
        assertThat(order.getCancelReason()).isEqualTo(SharedGroupOrderCancelReason.MULTIPLE_REASONS);
        assertThat(order.getCanceledAt()).isEqualTo(NOW.plusMinutes(1));
        assertThat(order.cancelSellerItems(8L, DeliveryCancelReason.SELLER_PRODUCT_ISSUE, NOW.plusMinutes(2))).isZero();
    }

    @Test
    @DisplayName("판매자 사유와 판매 중지가 섞여 모든 항목이 취소되면 공동 주문 사유는 여러 사유, 모두 판매 중지면 판매 중지")
    void cancelSellerItemsWithMixedReasonsCancelsOrderWithMultipleReasons() {
        SharedGroupOrder order = confirmedTwoSellers();
        order.cancelSellerItems(7L, DeliveryCancelReason.SELLER_OTHER, NOW);

        order.cancelSellerItems(8L, DeliveryCancelReason.SALES_STOPPED, NOW.plusMinutes(1));

        assertThat(order.getCancelReason()).isEqualTo(SharedGroupOrderCancelReason.MULTIPLE_REASONS);

        SharedGroupOrder salesStopped = confirmedTwoSellers();
        salesStopped.cancelSellerItems(7L, DeliveryCancelReason.SALES_STOPPED, NOW);
        salesStopped.cancelSellerItems(8L, DeliveryCancelReason.SALES_STOPPED, NOW.plusMinutes(1));
        assertThat(salesStopped.getCancelReason()).isEqualTo(SharedGroupOrderCancelReason.SALES_STOPPED);
    }

    @Test
    @DisplayName("결제 중인 공동 주문은 판매자 몫을 취소하지 않는다")
    void cancelSellerItemsIgnoresPayingOrder() {
        SharedGroupOrder order = sharedOrder(10L, CREATOR, MEMBER, CLOSED_AT,
                sharedLine(2L, 4L, 7L, 1, 10_000L));

        assertThat(order.cancelSellerItems(7L, DeliveryCancelReason.SELLER_OTHER, NOW)).isZero();
        assertThat(order.getStatus()).isEqualTo(SharedGroupOrderStatus.PAYING);
        assertThat(order.getItems()).noneMatch(SharedGroupOrderItem::isCanceled);
    }

    @Test
    @DisplayName("판매글 내림 요청에 싣는 항목은 그 판매자의 아직 취소되지 않은 공동 주문 항목뿐이다")
    void uncanceledItemIdsOf_excludesCanceledAndOtherSellers() {
        SharedGroupOrder order = sharedOrder(10L, CREATOR, MEMBER, CLOSED_AT,
                sharedLine(2L, 4L, 7L, 1, 1_000L), sharedLine(3L, 5L, 7L, 1, 1_000L),
                sharedLine(4L, 6L, 7L, 1, 1_000L), sharedLine(5L, 7L, 8L, 1, 1_000L));
        for (int i = 0; i < 4; i++) {
            org.springframework.test.util.ReflectionTestUtils.setField(order.getItems().get(i), "id", 701L + i);
        }
        order.markStockReserved();
        order.pay(CREATOR, 1L, CLOSED_AT, address());
        order.pay(MEMBER, 2L, CLOSED_AT, null);
        order.cancelItems(7L, java.util.List.of(701L), DeliveryCancelReason.SELLER_OTHER, NOW);

        assertThat(order.uncanceledItemIdsOf(7L)).containsExactly(702L, 703L);
    }

    private static SharedGroupOrder confirmedTwoSellers() {
        SharedGroupOrder order = sharedOrder(10L, CREATOR, MEMBER, CLOSED_AT,
                sharedLine(2L, 4L, 7L, 1, 10_000L), sharedLine(3L, 5L, 8L, 2, 5_000L));
        order.markStockReserved();
        order.pay(CREATOR, 1L, CLOSED_AT, address());
        order.pay(MEMBER, 2L, CLOSED_AT, null);
        return order;
    }
}
