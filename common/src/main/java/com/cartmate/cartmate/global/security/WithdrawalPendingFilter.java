package com.cartmate.cartmate.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 탈퇴 대기 중인 회원은 조회(GET)와 탈퇴·로그인·예치금 환급 요청만 허용한다.
 * 주문·장바구니·공동구매·판매글 등록 같은 변경 요청을 한곳에서 막는다.
 *
 * <p>탈퇴 대기 여부는 두 가지로 판단한다.
 * <ul>
 *   <li>토큰의 wp 표시: 회원 서비스가 탈퇴 신청·취소 응답과 로그인·재발급 때 그 시점 상태로 새 토큰을 준다</li>
 *   <li>wp가 없는 토큰이면 Redis 표시({@link SuspendedMemberChecker#isWithdrawalPending}):
 *       신청 전에 받은 토큰을 다른 기기에서 쓰는 동안(최대 Access Token 수명)에도 막는다.
 *       변경 요청에서만 확인하므로 조회 요청에는 비용이 없다. Redis 장애면 통과시킨다 (확정 직전 재검사가 따로 있다)</li>
 * </ul>
 */
public class WithdrawalPendingFilter extends OncePerRequestFilter {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    // 탈퇴 대기 중에도 허용: 탈퇴 취소, 로그인·로그아웃·토큰 재발급, 예치금 환급 신청
    // (환급: 유예 기간에 환불로 잔액이 생기면 탈퇴 확정이 막히므로, 취소하지 않고 돌려받을 수 있게)
    private static final List<String> ALLOWED_PATHS = List.of(
            "/api/members/me/withdrawal", "/api/auth/", "/api/wallets/me/withdraws");

    private final SuspendedMemberChecker memberChecker;

    public WithdrawalPendingFilter(SuspendedMemberChecker memberChecker) {
        this.memberChecker = memberChecker;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isBlocked(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"resultCode\":\"403-MEMBER_WITHDRAWAL_PENDING\",\"msg\":\"탈퇴 대기 중에는 이용할 수 없습니다. 탈퇴를 취소하면 다시 이용할 수 있습니다.\",\"data\":null}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isBlocked(HttpServletRequest request) {
        if (READ_METHODS.contains(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        if (ALLOWED_PATHS.stream().anyMatch(path::startsWith)) {
            return false;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof SecurityUser user)) {
            return false;
        }
        return user.withdrawalPending() || memberChecker.isWithdrawalPending(user.memberId());
    }
}
