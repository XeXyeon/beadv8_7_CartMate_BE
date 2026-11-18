package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.global.http.InternalApiClient;
import com.cartmate.cartmate.global.http.InternalApiClient.InternalServerUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * 탈퇴 완료 회원 조회의 호출 형식. 회원 서버 API(/internal/members/withdrawn)는 아직 없어 형식만 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class HttpSalesPostMemberClientTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 11, 18, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 11, 19, 0, 0);

    @Mock
    private InternalApiClient memberServerApiClient;

    private HttpSalesPostMemberClient memberClient;

    @BeforeEach
    void setUp() {
        memberClient = new HttpSalesPostMemberClient(memberServerApiClient);
    }

    @Test
    @DisplayName("기간을 ISO 시각(from 이상 ~ to 미만)으로 넘겨 탈퇴 완료 회원 ID를 받는다")
    void asksWithdrawnMembersInPeriod() {
        given(memberServerApiClient.get(eq(HttpSalesPostMemberClient.WITHDRAWN_PATH),
                eq(HttpSalesPostMemberClient.MEMBER_IDS), eq("2026-11-18T00:00:00"), eq("2026-11-19T00:00:00")))
                .willReturn(List.of(10L, 20L));

        assertThat(memberClient.findWithdrawnMemberIds(FROM, TO)).containsExactly(10L, 20L);
    }

    @Test
    @DisplayName("from이 없으면(첫 실행) to만 넘겨 처음부터 받는다")
    void asksFromStartWhenFromIsNull() {
        given(memberServerApiClient.get(eq(HttpSalesPostMemberClient.WITHDRAWN_FROM_START_PATH),
                eq(HttpSalesPostMemberClient.MEMBER_IDS), eq("2026-11-19T00:00:00")))
                .willReturn(List.of(10L));

        assertThat(memberClient.findWithdrawnMemberIds(null, TO)).containsExactly(10L);
    }

    @Test
    @DisplayName("응답 데이터가 비어 있으면 빈 목록")
    void emptyWhenNoData() {
        given(memberServerApiClient.get(eq(HttpSalesPostMemberClient.WITHDRAWN_PATH),
                eq(HttpSalesPostMemberClient.MEMBER_IDS), eq("2026-11-18T00:00:00"), eq("2026-11-19T00:00:00")))
                .willReturn(null);

        assertThat(memberClient.findWithdrawnMemberIds(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("회원 서버가 응답하지 않으면 503 예외가 그대로 올라간다 (스케줄러가 실패로 보고 성공 시각을 바꾸지 않게)")
    void propagatesUnavailable() {
        InternalServerUnavailableException unavailable = new InternalServerUnavailableException("MEMBER", "회원", null);
        given(memberServerApiClient.get(eq(HttpSalesPostMemberClient.WITHDRAWN_PATH),
                eq(HttpSalesPostMemberClient.MEMBER_IDS), eq("2026-11-18T00:00:00"), eq("2026-11-19T00:00:00")))
                .willThrow(unavailable);

        assertThatThrownBy(() -> memberClient.findWithdrawnMemberIds(FROM, TO)).isSameAs(unavailable);
    }
}
