package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostPurchase;
import com.cartmate.cartmate.boundedContext.productService.post.in.SalesPostEventListener;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostPurchaseRepository;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import com.cartmate.cartmate.shared.delivery.event.DeliveryConfirmedEvent;
import com.cartmate.cartmate.shared.order.OrderType;
import com.cartmate.cartmate.shared.order.event.OrderPaidEvent;
import com.cartmate.cartmate.shared.order.event.SharedGroupOrderConfirmedEvent;
import com.cartmate.cartmate.support.ProductServiceTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * 리뷰 자격용 구매 기록: 결제 완료 · 공동 주문 확정으로 구매 정보를, 구매확정으로 확정 시각을 채운다.
 * 이벤트 도착 순서 · 중복 · 구매 유형별 주문항목 id 겹침 · 리스너가 예외를 던지지 않는지 확인한다.
 * (다른 리스너(재고 판매 확정 · 페이백)와 섞이지 않게 이 리스너를 직접 부른다)
 */
class SalesPostPurchaseRecordTest extends ProductServiceTestSupport {

    private static final Long BUYER_ID = 100L;
    private static final Long CREATOR_ID = 300L;
    private static final Long SELLER_ID = 7L;
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 11, 1, 10, 0);
    private static final LocalDateTime CONFIRMED_AT = LocalDateTime.of(2026, 11, 8, 10, 0);

    @Autowired
    SalesPostEventListener listener;
    @Autowired
    SalesPostPurchaseRepository salesPostPurchaseRepository;

    @BeforeEach
    void setUp() {
        salesPostPurchaseRepository.deleteAll();
    }

    @Test
    @DisplayName("결제 완료 후 구매확정이 오면 구매 정보와 확정 시각이 한 줄에 모이고, 확정 이벤트에 없는(전액 환불) 항목은 확정되지 않는다")
    void paidThenConfirmed() {
        listener.handle(orderPaid(31L, item(501L, 9L, "반팔 티셔츠", "블랙 / L"), item(502L, 10L, "바지", "M")));
        listener.handle(deliveryConfirmed(PurchaseType.INDIVIDUAL_GROUP, 31L, 501L));

        SalesPostPurchase tee = find(PurchaseType.INDIVIDUAL_GROUP, 501L);
        assertThat(tee.getOrderId()).isEqualTo(31L);
        assertThat(tee.getBuyerMemberId()).isEqualTo(BUYER_ID);
        assertThat(tee.getProductId()).isEqualTo(9L);
        assertThat(tee.getInventoryId()).isEqualTo(9L * 10);
        assertThat(tee.getSellerId()).isEqualTo(SELLER_ID);
        assertThat(tee.getProductName()).isEqualTo("반팔 티셔츠");
        assertThat(tee.getOptionName()).isEqualTo("블랙 / L");
        assertThat(tee.getConfirmedAt()).isEqualTo(CONFIRMED_AT);

        SalesPostPurchase refundedPants = find(PurchaseType.INDIVIDUAL_GROUP, 502L);
        assertThat(refundedPants.hasPurchase()).isTrue();
        assertThat(refundedPants.isConfirmed()).isFalse();
    }

    @Test
    @DisplayName("구매확정이 결제 완료보다 먼저 와도 나중에 구매 정보가 채워져 같은 결과가 된다")
    void confirmedThenPaid() {
        listener.handle(deliveryConfirmed(PurchaseType.INDIVIDUAL_GROUP, 31L, 501L));

        SalesPostPurchase onlyConfirmed = find(PurchaseType.INDIVIDUAL_GROUP, 501L);
        assertThat(onlyConfirmed.isConfirmed()).isTrue();
        assertThat(onlyConfirmed.hasPurchase()).isFalse();

        listener.handle(orderPaid(31L, item(501L, 9L, "반팔 티셔츠", "블랙 / L")));

        SalesPostPurchase completed = find(PurchaseType.INDIVIDUAL_GROUP, 501L);
        assertThat(completed.hasPurchase()).isTrue();
        assertThat(completed.getBuyerMemberId()).isEqualTo(BUYER_ID);
        assertThat(completed.getConfirmedAt()).isEqualTo(CONFIRMED_AT);
        assertThat(salesPostPurchaseRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 이벤트가 두 번 와도 한 줄만 남고, 처음 확정 시각을 유지한다")
    void duplicateEventsAreIgnored() {
        listener.handle(orderPaid(31L, item(501L, 9L, "반팔 티셔츠", "블랙 / L")));
        listener.handle(orderPaid(31L, item(501L, 9L, "반팔 티셔츠", "블랙 / L")));
        listener.handle(deliveryConfirmed(PurchaseType.INDIVIDUAL_GROUP, 31L, 501L));
        listener.handle(new DeliveryConfirmedEvent(1L, SELLER_ID, 31L, PurchaseType.INDIVIDUAL_GROUP, PAID_AT,
                CONFIRMED_AT.plusDays(3), List.of(new DeliveryConfirmedEvent.Item(501L, "반팔 티셔츠", 10000L, 1L))));

        assertThat(salesPostPurchaseRepository.count()).isEqualTo(1);
        assertThat(find(PurchaseType.INDIVIDUAL_GROUP, 501L).getConfirmedAt()).isEqualTo(CONFIRMED_AT);
    }

    @Test
    @DisplayName("공동 주문은 주문을 만든 회원이 구매자이고, 개인 주문과 주문항목 id가 같아도 따로 저장된다")
    void sharedGroupOrderIsSeparateFromPersonal() {
        listener.handle(orderPaid(31L, item(801L, 9L, "바지", "M")));
        listener.handle(new SharedGroupOrderConfirmedEvent(55L, 5L, CREATOR_ID, PAID_AT, null, List.of(
                new SharedGroupOrderConfirmedEvent.Item(801L, 11L, 110L, SELLER_ID, "반팔 티셔츠", "화이트 / XL", 3, 9000L))));
        listener.handle(deliveryConfirmed(PurchaseType.SHARED_GROUP, 55L, 801L));

        assertThat(salesPostPurchaseRepository.findAll())
                .extracting(SalesPostPurchase::getPurchaseType, SalesPostPurchase::getOrderId,
                        SalesPostPurchase::getBuyerMemberId, SalesPostPurchase::getProductName,
                        SalesPostPurchase::isConfirmed)
                .containsExactlyInAnyOrder(
                        tuple(PurchaseType.INDIVIDUAL_GROUP, 31L, BUYER_ID, "바지", false),
                        tuple(PurchaseType.SHARED_GROUP, 55L, CREATOR_ID, "반팔 티셔츠", true));
    }

    @Test
    @DisplayName("구매 기록 저장이 실패해도 리스너는 예외를 던지지 않는다 (같은 트랜잭션의 재고 판매 확정을 롤백시키지 않게)")
    void listenerDoesNotThrow() {
        doThrow(new IllegalStateException("저장 실패")).when(salesPostPurchaseRecordUseCase)
                .recordPurchases(any(), any(), any(), any());
        doThrow(new IllegalStateException("저장 실패")).when(salesPostPurchaseRecordUseCase)
                .confirmPurchases(any(), any(), any(), any());

        assertThatCode(() -> listener.handle(orderPaid(31L, item(501L, 9L, "반팔 티셔츠", "블랙 / L"))))
                .doesNotThrowAnyException();
        assertThatCode(() -> listener.handle(deliveryConfirmed(PurchaseType.INDIVIDUAL_GROUP, 31L, 501L)))
                .doesNotThrowAnyException();
        assertThat(salesPostPurchaseRepository.count()).isZero();
    }

    private SalesPostPurchase find(PurchaseType purchaseType, Long orderItemId) {
        return salesPostPurchaseRepository.findByPurchaseTypeAndOrderItemId(purchaseType, orderItemId).orElseThrow();
    }

    private static OrderPaidEvent orderPaid(Long orderId, OrderPaidEvent.Item... items) {
        return new OrderPaidEvent(orderId, "ORD-" + orderId, BUYER_ID, OrderType.PERSONAL, null, PAID_AT, null,
                List.of(items));
    }

    private static OrderPaidEvent.Item item(Long orderItemId, Long productId, String productName, String optionName) {
        return new OrderPaidEvent.Item(orderItemId, productId, productId * 10, SELLER_ID, productName, optionName,
                1, 10000L);
    }

    private static DeliveryConfirmedEvent deliveryConfirmed(PurchaseType purchaseType, Long orderId, Long... orderItemIds) {
        return new DeliveryConfirmedEvent(1L, SELLER_ID, orderId, purchaseType, PAID_AT, CONFIRMED_AT,
                java.util.Arrays.stream(orderItemIds)
                        .map(id -> new DeliveryConfirmedEvent.Item(id, "상품", 10000L, 1L))
                        .toList());
    }
}
