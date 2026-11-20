package com.cartmate.cartmate.boundedContext.orderService.order.domain.personal;

import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.Order.OrderLine;
import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import com.cartmate.cartmate.shared.delivery.event.PendingReturn;
import com.cartmate.cartmate.shared.order.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static com.cartmate.cartmate.boundedContext.orderService.order.support.OrderFixtures.addressWithoutDetail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderReturnTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 17, 12, 0);
    private static final Long BUYER = 1L;
    private static final Long ITEM_A = 701L;
    private static final Long ITEM_B = 702L;
    private static final Long ITEM_C = 703L;

    private static void ids(Order order, Long... ids) {
        for (int i = 0; i < ids.length; i++) {
            ReflectionTestUtils.setField(order.getItems().get(i), "id", ids[i]);
        }
    }

    // A 10,000원(판매자 7)·B 1,000원(판매자 8)·C 500원(판매자 9), "11,000원 이상 3,000원 할인" 플랫폼 쿠폰, 결제 8,500원
    // 결제 때 고정된 할인: A 2,608원, B 260원, C 130원 + 끝전 2원 = 132원
    private Order couponOrder() {
        Order order = Order.createPersonal("ORD-RT", BUYER, List.of(
                new OrderLine(10L, 11L, 7L, "A", null, 10_000L, 1),
                new OrderLine(20L, 21L, 8L, "B", null, 1_000L, 1),
                new OrderLine(30L, 31L, 9L, "C", null, 500L, 1)
        ), addressWithoutDetail(), NOW);
        ids(order, ITEM_A, ITEM_B, ITEM_C);
        order.applyCoupon(5L, 3_000L, true, null, OrderCouponRule.of("FIXED", 3_000L, null, 11_000L));
        order.markPaid(99L, NOW.plusMinutes(1));
        return order;
    }

    @Test
    @DisplayName("반품 환불은 반품 상품의 (금액 - 고정 할인) 합이다 (A·B 반품 → 7,392 + 740 = 8,132원)")
    void previewReturn_AB() {
        assertThat(couponOrder().previewReturn(List.of(ITEM_A, ITEM_B)).refundAmount()).isEqualTo(8_132L);
    }

    @Test
    @DisplayName("쿠폰 최소 금액이 깨지는 반품도 받는다 — 남은 상품의 할인은 그대로라 구매자가 더 내는 일이 없다 (B → 740원)")
    void previewBuyerReturn_belowMinimum_accepted() {
        assertThat(couponOrder().previewBuyerReturn(BUYER, List.of(ITEM_B)).refundAmount()).isEqualTo(740L);
    }

    @Test
    @DisplayName("구매자 본인이 아니면 예상 환불을 볼 수 없다")
    void previewBuyerReturn_notBuyer_throws() {
        Order order = couponOrder();

        assertThatThrownBy(() -> order.previewBuyerReturn(99L, List.of(ITEM_A)))
                .hasMessageContaining("ORDER_BUYER_ONLY");
    }

    @Test
    @DisplayName("반품을 반영하면 항목은 반품됨이 되고 주문은 결제 완료로 남는다. 할인이 남아 있으면 쿠폰은 돌려주지 않는다")
    void applyReturn_B() {
        Order order = couponOrder();

        OrderReturnOutcome outcome = order.applyReturn(List.of(ITEM_B), NOW.plusDays(3));

        assertThat(outcome.refundAmount()).isEqualTo(740L);
        assertThat(outcome.couponReturnable()).isFalse();
        assertThat(order.getItems().get(1).isReturned()).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("이미 반품된 항목을 다시 반품할 수 없다")
    void applyReturn_sameItemTwice_throws() {
        Order order = couponOrder();
        order.applyReturn(List.of(ITEM_A), NOW.plusDays(3));

        assertThatThrownBy(() -> order.applyReturn(List.of(ITEM_A), NOW.plusDays(4)))
                .hasMessageContaining("ORDER_RETURN_ITEM_INVALID");
    }

    @Test
    @DisplayName("결제 완료가 아닌 주문은 반품할 수 없다")
    void previewReturn_notPaid_throws() {
        Order order = Order.createPersonal("ORD-RT2", BUYER, List.of(
                new OrderLine(10L, 11L, 7L, "A", null, 10_000L, 1)), addressWithoutDetail(), NOW);
        ids(order, ITEM_A);

        assertThatThrownBy(() -> order.previewReturn(List.of(ITEM_A)))
                .hasMessageContaining("ORDER_RETURN_NOT_PAID");
    }

    @Test
    @DisplayName("전부 반품돼도 주문은 결제 완료로 남고, 남은 결제 금액 전부를 환불하며 쿠폰을 돌려준다")
    void applyReturn_all_keepsPaidAndReturnsCoupon() {
        Order order = Order.createPersonal("ORD-RT3", BUYER, List.of(
                new OrderLine(10L, 11L, 7L, "D", null, 6_000L, 2)), addressWithoutDetail(), NOW);
        ids(order, ITEM_A);
        order.applyCoupon(5L, 2_000L, true, null, OrderCouponRule.of("FIXED", 2_000L, null, 10_000L));
        order.markPaid(99L, NOW.plusMinutes(1));

        OrderReturnOutcome outcome = order.applyReturn(List.of(ITEM_A), NOW.plusDays(3));

        assertThat(outcome.refundAmount()).isEqualTo(10_000L);
        assertThat(outcome.couponReturnable()).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("반품된 항목이 있는 주문에서 나머지를 취소하면 남은 결제 금액만 돌려주고 주문은 결제 완료로 남는다")
    void cancelAfterReturn_refundsRemainingOnly() {
        Order order = Order.createPersonal("ORD-RT4", BUYER, List.of(
                new OrderLine(10L, 11L, 7L, "A", null, 10_000L, 2),
                new OrderLine(20L, 21L, 8L, "B", null, 5_000L, 1)
        ), addressWithoutDetail(), NOW);
        ids(order, ITEM_A, ITEM_B);
        order.markPaid(99L, NOW.plusMinutes(1));

        long returned = order.applyReturn(List.of(ITEM_A), NOW.plusDays(3)).refundAmount();
        ItemCancelOutcome canceled = order.cancelItems(8L, null, DeliveryCancelReason.SELLER_OUT_OF_STOCK,
                NOW.plusDays(4));

        assertThat(returned).isEqualTo(20_000L);
        assertThat(canceled.refundAmount()).isEqualTo(5_000L);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getItems().get(1).isCanceled()).isTrue();
    }

    // A 2,000원(판매자 7)·B 2,000원(판매자 8)·C 10,000원(판매자 9), "12,000원 이상 3,000원 할인" 플랫폼 쿠폰, 결제 11,000원
    // 결제 때 고정된 할인: A 428원, B 428원, C 2,142원 + 끝전 2원 = 2,144원
    private Order minimumOrder() {
        Order order = Order.createPersonal("ORD-RT5", BUYER, List.of(
                new OrderLine(10L, 11L, 7L, "A", null, 2_000L, 1),
                new OrderLine(20L, 21L, 8L, "B", null, 2_000L, 1),
                new OrderLine(30L, 31L, 9L, "C", null, 10_000L, 1)
        ), addressWithoutDetail(), NOW);
        ids(order, ITEM_A, ITEM_B, ITEM_C);
        order.applyCoupon(5L, 3_000L, true, null, OrderCouponRule.of("FIXED", 3_000L, null, 12_000L));
        order.markPaid(99L, NOW.plusMinutes(1));
        return order;
    }

    @Test
    @DisplayName("처리 중인 반품이 있어도 반품을 쪼개 신청해 더 받는 일이 없다 — 각 상품은 자기 고정 할인만 빠진다 (B → 1,572원)")
    void previewReturn_pendingDoesNotChangeFixedRefund() {
        Order order = minimumOrder();

        OrderReturnPreview alone = order.previewReturn(List.of(ITEM_B), List.of());
        OrderReturnPreview afterA = order.previewReturn(List.of(ITEM_B), List.of(new PendingReturn(false, List.of(ITEM_A))));
        OrderReturnPreview together = order.previewReturn(List.of(ITEM_A, ITEM_B));

        assertThat(alone.refundAmount()).isEqualTo(1_572L).isEqualTo(afterA.refundAmount());
        assertThat(together.refundAmount()).isEqualTo(2 * 1_572L);
    }

    @Test
    @DisplayName("이미 반품이 반영된 항목이 처리 중 목록에 남아 있어도 한 번만 센다")
    void previewReturn_pendingAlreadyReturned_isIgnored() {
        Order order = minimumOrder();
        order.applyReturn(List.of(ITEM_A), NOW.plusDays(3));
        List<PendingReturn> stale = List.of(new PendingReturn(false, List.of(ITEM_A)));

        assertThat(order.previewReturn(List.of(ITEM_B), stale))
                .isEqualTo(order.previewReturn(List.of(ITEM_B), List.of()));
    }
}
