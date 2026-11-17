package com.cartmate.cartmate.boundedContext.etcService.delivery.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimReasonTest {

    @Test
    @DisplayName("상품 불량·파손과 오배송·누락만 판매자 탓이다")
    void sellerFault_onlyDefectAndWrongDelivery() {
        assertThat(Arrays.stream(ClaimReason.values()).filter(ClaimReason::isSellerFault))
                .containsExactlyInAnyOrder(ClaimReason.PRODUCT_DEFECT, ClaimReason.WRONG_DELIVERY);
    }
}
