package com.cartmate.cartmate.boundedContext.paymentService.payout.in;

import com.cartmate.cartmate.shared.delivery.event.DeliveryConfirmedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

// 쿠폰 할인 칸을 추가하면서 예전 생성자를 남겼다. Kafka로 받은 JSON이 할인 칸 없이 와도(배포 순서) 읽혀야 한다.
class DeliveryConfirmedEventJsonTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("할인 칸이 없는 예전 이벤트는 할인 null로, 새 이벤트는 할인 값 그대로 읽는다")
    void readsOldAndNewItems() {
        String oldJson = "{\"orderItemId\":11,\"productName\":\"사과\",\"unitPrice\":1000,\"quantity\":2}";
        String newJson = "{\"orderItemId\":11,\"productName\":\"사과\",\"unitPrice\":1000,\"quantity\":2,"
                + "\"sellerDiscountAmount\":300,\"platformDiscountAmount\":null}";

        DeliveryConfirmedEvent.Item oldItem = objectMapper.readValue(oldJson, DeliveryConfirmedEvent.Item.class);
        DeliveryConfirmedEvent.Item newItem = objectMapper.readValue(newJson, DeliveryConfirmedEvent.Item.class);

        assertThat(oldItem.sellerDiscountAmount()).isNull();
        assertThat(oldItem.quantity()).isEqualTo(2L);
        assertThat(newItem.sellerDiscountAmount()).isEqualTo(300L);
        assertThat(objectMapper.writeValueAsString(newItem)).contains("\"sellerDiscountAmount\":300");
    }
}
