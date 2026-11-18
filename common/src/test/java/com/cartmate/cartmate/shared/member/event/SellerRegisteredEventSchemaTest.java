package com.cartmate.cartmate.shared.member.event;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 판매자 등록 이벤트는 outbox_event · Kafka · 다른 서비스 DB로 퍼진다 → 계좌 · 사업자번호 · 전화번호를 다시 넣지 않게
class SellerRegisteredEventSchemaTest {

    @Test
    void 계좌_사업자번호_전화번호_칸이_없다() {
        List<String> fields = Arrays.stream(SellerRegisteredEvent.class.getRecordComponents())
                .map(RecordComponent::getName)
                .map(String::toLowerCase)
                .toList();

        assertThat(fields).noneMatch(name -> name.contains("account")
                || name.contains("bank")
                || name.contains("businessnumber")
                || name.contains("phone"));
    }
}
