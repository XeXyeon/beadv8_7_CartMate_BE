package com.cartmate.cartmate.boundedContext.etcService.delivery.domain;

import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryItemCancelTest {

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 11, 16, 10, 0);
    private static final LocalDateTime T2 = T1.plusMinutes(5);

    private Delivery delivery;
    private DeliveryItem apple;
    private DeliveryItem pear;

    @BeforeEach
    void setUp() {
        delivery = Delivery.create(7L, 1007L, 100L, "O-100", PurchaseType.INDIVIDUAL_GROUP,
                "수령인", "010", "00000", "서울", "101호", "key-100", "hash");
        apple = DeliveryItem.create(delivery, 11L, "사과", 1000L, 3L);
        pear = DeliveryItem.create(delivery, 12L, "배", 2000L, 1L);
    }

    @Test
    @DisplayName("일부 상품만 취소하면 그 상품만 표시하고 배송은 그대로 진행한다")
    void cancelItems_some_keepsDeliveryActive() {
        List<DeliveryItem> canceled = delivery.cancelItems(Set.of(11L), DeliveryCancelReason.BUYER_CANCELED, T1);

        assertThat(canceled).containsExactly(apple);
        assertThat(apple.isCanceled()).isTrue();
        assertThat(apple.getCanceledAt()).isEqualTo(T1);
        assertThat(apple.getCancelReason()).isEqualTo(DeliveryCancelReason.BUYER_CANCELED);
        assertThat(pear.isCanceled()).isFalse();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.REQUESTED);
    }

    @Test
    @DisplayName("남은 마지막 상품을 취소하면 배송도 그 사유와 시각으로 취소한다")
    void cancelItems_lastRemaining_cancelsDelivery() {
        delivery.cancelItems(Set.of(11L), DeliveryCancelReason.BUYER_CANCELED, T1);

        delivery.cancelItems(Set.of(12L), DeliveryCancelReason.SELLER_OUT_OF_STOCK, T2);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.CANCELED);
        assertThat(delivery.getCanceledAt()).isEqualTo(T2);
        assertThat(delivery.getCancelReason()).isEqualTo(DeliveryCancelReason.SELLER_OUT_OF_STOCK);
        assertThat(apple.getCancelReason()).isEqualTo(DeliveryCancelReason.BUYER_CANCELED);
    }

    @Test
    @DisplayName("이미 취소된 상품을 다시 취소하면 아무것도 돌려주지 않고 처음 기록을 그대로 둔다")
    void cancelItems_again_returnsEmpty() {
        delivery.cancelItems(Set.of(11L), DeliveryCancelReason.BUYER_CANCELED, T1);

        List<DeliveryItem> again = delivery.cancelItems(Set.of(11L), DeliveryCancelReason.BUYER_CANCELED, T2);

        assertThat(again).isEmpty();
        assertThat(apple.getCanceledAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("발송된 배송의 상품은 취소할 수 없다")
    void cancelItems_shipped_throws() {
        delivery.prepare("CJ", "123");
        delivery.ship();

        assertThatThrownBy(() -> delivery.cancelItems(Set.of(11L), DeliveryCancelReason.BUYER_CANCELED, T1))
                .isInstanceOf(ServiceException.class);
        assertThat(apple.isCanceled()).isFalse();
    }

    @Test
    @DisplayName("판매글 내림으로 배송을 통째로 취소하면 모든 상품도 같은 사유로 취소 표시한다")
    void cancelByPostHidden_marksAllItemsCanceled() {
        delivery.cancelByPostHidden(T1);

        assertThat(apple.getCancelReason()).isEqualTo(DeliveryCancelReason.SALES_STOPPED);
        assertThat(pear.getCanceledAt()).isEqualTo(T1);
    }
}
