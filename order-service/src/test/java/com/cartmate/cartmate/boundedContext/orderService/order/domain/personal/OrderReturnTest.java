package com.cartmate.cartmate.boundedContext.orderService.order.domain.personal;

import com.cartmate.cartmate.boundedContext.orderService.order.domain.itemcancel.CouponMode;
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
    private static final Long ITEM_D = 704L;

    private static void ids(Order order, Long... ids) {
        for (int i = 0; i < ids.length; i++) {
            ReflectionTestUtils.setField(order.getItems().get(i), "id", ids[i]);
        }
    }

    // A 10,000원(판매자 7)·B 1,000원(판매자 8)·C 500원(판매자 9), "11,000원 이상 3,000원 할인" 플랫폼 쿠폰, 결제 8,500원
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
    @DisplayName("구매자 사정 반품은 남은 상품으로 쿠폰을 다시 계산한다 (A·B 반품, 남은 C 500원은 조건 미달 → 8,500 − 500 = 8,000원)")
    void previewReturn_recalc_AB_is8000() {
        OrderReturnPreview preview = couponOrder().previewReturn(List.of(ITEM_A, ITEM_B), CouponMode.RECALCULATE);

        assertThat(preview.refundAmount()).isEqualTo(8_000L);
        assertThat(preview.underpaid()).isFalse();
    }

    @Test
    @DisplayName("B만 반품하면 남은 A·C 10,500원이 조건 미달이라 오히려 2,000원을 더 내야 한다")
    void previewReturn_recalc_B_isUnderpaid() {
        OrderReturnPreview preview = couponOrder().previewReturn(List.of(ITEM_B), CouponMode.RECALCULATE);

        assertThat(preview.refundAmount()).isZero();
        assertThat(preview.underpaid()).isTrue();
    }

    @Test
    @DisplayName("구매자 예상 환불은 더 내야 하는 반품을 쿠폰 최소 금액 오류로 받지 않는다")
    void previewBuyerReturn_underpaid_throws() {
        Order order = couponOrder();

        assertThatThrownBy(() -> order.previewBuyerReturn(BUYER, List.of(ITEM_B), CouponMode.RECALCULATE))
                .hasMessageContaining("ORDER_RETURN_COUPON_MINIMUM");
    }

    @Test
    @DisplayName("구매자 본인이 아니면 예상 환불을 볼 수 없다")
    void previewBuyerReturn_notBuyer_throws() {
        Order order = couponOrder();

        assertThatThrownBy(() -> order.previewBuyerReturn(99L, List.of(ITEM_A), CouponMode.RECALCULATE))
                .hasMessageContaining("ORDER_BUYER_ONLY");
    }

    @Test
    @DisplayName("판매자가 일부만 승인해 더 내야 하는 반품은 0원으로 처리하고 항목은 반품됨이 된다")
    void applyReturn_recalc_B_refundsZero() {
        Order order = couponOrder();

        OrderReturnOutcome outcome = order.applyReturn(List.of(ITEM_B), CouponMode.RECALCULATE, NOW.plusDays(3));

        assertThat(outcome.refundAmount()).isZero();
        assertThat(order.getItems().get(1).isReturned()).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("판매자 탓 반품은 할인을 비율로 나눈다 (B 1,000원 − 3,000×1,000/11,500 = 740원)")
    void applyReturn_proportional_B_is740() {
        OrderReturnOutcome outcome = couponOrder().applyReturn(List.of(ITEM_B), CouponMode.PROPORTIONAL, NOW.plusDays(3));

        assertThat(outcome.refundAmount()).isEqualTo(740L);
    }

    @Test
    @DisplayName("이미 반품된 항목을 다시 반품할 수 없다")
    void applyReturn_sameItemTwice_throws() {
        Order order = couponOrder();
        order.applyReturn(List.of(ITEM_A), CouponMode.PROPORTIONAL, NOW.plusDays(3));

        assertThatThrownBy(() -> order.applyReturn(List.of(ITEM_A), CouponMode.PROPORTIONAL, NOW.plusDays(4)))
                .hasMessageContaining("ORDER_RETURN_ITEM_INVALID");
    }

    @Test
    @DisplayName("결제 완료가 아닌 주문은 반품할 수 없다")
    void previewReturn_notPaid_throws() {
        Order order = Order.createPersonal("ORD-RT2", BUYER, List.of(
                new OrderLine(10L, 11L, 7L, "A", null, 10_000L, 1)), addressWithoutDetail(), NOW);
        ids(order, ITEM_A);

        assertThatThrownBy(() -> order.previewReturn(List.of(ITEM_A), CouponMode.RECALCULATE))
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

        OrderReturnOutcome outcome = order.applyReturn(List.of(ITEM_A), CouponMode.RECALCULATE, NOW.plusDays(3));

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

        long returned = order.applyReturn(List.of(ITEM_A), CouponMode.PROPORTIONAL, NOW.plusDays(3)).refundAmount();
        ItemCancelOutcome canceled = order.cancelItems(8L, null, DeliveryCancelReason.SELLER_OUT_OF_STOCK,
                CouponMode.PROPORTIONAL, NOW.plusDays(4));

        assertThat(returned).isEqualTo(20_000L);
        assertThat(canceled.refundAmount()).isEqualTo(5_000L);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getItems().get(1).isCanceled()).isTrue();
    }

    // A 2,000원(판매자 7)·B 2,000원(판매자 8)·C 10,000원(판매자 9), "12,000원 이상 3,000원 할인" 플랫폼 쿠폰, 결제 11,000원
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
    @DisplayName("처리 중인 반품이 없으면 B 반품은 남은 A·C 12,000원이 조건을 채워 2,000원이 환불된다")
    void previewReturn_noPending_isAccepted() {
        OrderReturnPreview preview = minimumOrder().previewReturn(List.of(ITEM_B), List.of(), CouponMode.RECALCULATE);

        assertThat(preview.refundAmount()).isEqualTo(2_000L);
        assertThat(preview.underpaid()).isFalse();
    }

    @Test
    @DisplayName("A 반품이 먼저 처리 중이면 B 반품은 남은 C 10,000원이 조건 미달이라 부족해진다 (쪼개 신청해 할인을 더 받는 길을 막는다)")
    void previewReturn_pendingFirst_makesLaterReturnUnderpaid() {
        OrderReturnPreview preview = minimumOrder().previewReturn(
                List.of(ITEM_B), List.of(new PendingReturn(false, List.of(ITEM_A))), CouponMode.RECALCULATE);

        assertThat(preview.refundAmount()).isZero();
        assertThat(preview.underpaid()).isTrue();
    }

    @Test
    @DisplayName("처리 중인 반품을 반영해도 남은 금액이 최소 금액을 넘으면 통과한다 (남은 C·D 12,000원)")
    void previewReturn_pendingKeepsCouponValid_stillAccepted() {
        Order order = Order.createPersonal("ORD-RT6", BUYER, List.of(
                new OrderLine(10L, 11L, 7L, "A", null, 2_000L, 1),
                new OrderLine(20L, 21L, 8L, "B", null, 2_000L, 1),
                new OrderLine(30L, 31L, 9L, "C", null, 10_000L, 1),
                new OrderLine(40L, 41L, 9L, "D", null, 2_000L, 1)
        ), addressWithoutDetail(), NOW);
        ids(order, ITEM_A, ITEM_B, ITEM_C, ITEM_D);
        order.applyCoupon(5L, 3_000L, true, null, OrderCouponRule.of("FIXED", 3_000L, null, 12_000L));
        order.markPaid(99L, NOW.plusMinutes(1));

        OrderReturnPreview preview = order.previewReturn(
                List.of(ITEM_B), List.of(new PendingReturn(false, List.of(ITEM_A))), CouponMode.RECALCULATE);

        assertThat(preview.refundAmount()).isEqualTo(2_000L);
        assertThat(preview.underpaid()).isFalse();
    }

    @Test
    @DisplayName("판매자 탓으로 처리 중인 반품은 비율로 먼저 반영하고, 그래도 B 반품은 조건 미달이면 부족해진다")
    void previewReturn_pendingSellerFault_isAppliedProportionally() {
        OrderReturnPreview preview = minimumOrder().previewReturn(
                List.of(ITEM_B), List.of(new PendingReturn(true, List.of(ITEM_A))), CouponMode.RECALCULATE);

        assertThat(preview.underpaid()).isTrue();
    }

    @Test
    @DisplayName("이미 반품이 반영된 항목이 처리 중 목록에 남아 있어도 한 번만 센다")
    void previewReturn_pendingAlreadyReturned_isIgnored() {
        Order order = minimumOrder();
        order.applyReturn(List.of(ITEM_A), CouponMode.RECALCULATE, NOW.plusDays(3));
        List<PendingReturn> stale = List.of(new PendingReturn(false, List.of(ITEM_A)));

        assertThat(order.previewReturn(List.of(ITEM_B), stale, CouponMode.RECALCULATE))
                .isEqualTo(order.previewReturn(List.of(ITEM_B), List.of(), CouponMode.RECALCULATE));
    }
}
