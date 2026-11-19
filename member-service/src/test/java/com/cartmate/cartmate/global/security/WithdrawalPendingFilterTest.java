package com.cartmate.cartmate.global.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 탈퇴 대기 차단: wp 없는 토큰(신청 전에 받은 다른 기기 토큰)도 Redis 표시로 막고, 조회·허용 경로는 Redis를 보지 않는다
// (common에는 테스트 설정이 없어 회원 서비스에 둔다)
class WithdrawalPendingFilterTest {

    private static final long MEMBER = 1L;

    private final SuspendedMemberChecker checker = mock(SuspendedMemberChecker.class);
    private final WithdrawalPendingFilter filter = new WithdrawalPendingFilter(checker);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void login(boolean wp) {
        SecurityUser user = new SecurityUser(MEMBER, "a@a.com", "BUYER", null, List.of("pwd"), wp);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private int status(String method, String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(method, path), response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void 신청_전에_받은_토큰이라도_Redis에_탈퇴_대기면_변경_요청을_막는다() throws Exception {
        login(false);
        given(checker.isWithdrawalPending(MEMBER)).willReturn(true);

        assertThat(status("POST", "/api/orders")).isEqualTo(403);
        assertThat(status("GET", "/api/orders")).isEqualTo(200);
    }

    @Test
    void 탈퇴_취소_예치금_환급은_탈퇴_대기여도_허용한다() throws Exception {
        login(true);

        assertThat(status("DELETE", "/api/members/me/withdrawal")).isEqualTo(200);
        assertThat(status("POST", "/api/wallets/me/withdraws")).isEqualTo(200);
        assertThat(status("POST", "/api/wallets/me/withdraws/confirm")).isEqualTo(200);
        assertThat(status("POST", "/api/carts/items")).isEqualTo(403);
    }

    @Test
    void 조회_요청과_wp가_있는_토큰은_Redis를_보지_않는다() throws Exception {
        login(false);
        status("GET", "/api/orders");
        login(true);
        status("POST", "/api/orders");

        verify(checker, never()).isWithdrawalPending(any());
    }
}
