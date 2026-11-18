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
 * 탈퇴 대기 중인 회원은 조회(GET)와 탈퇴·로그인 관련 요청만 허용한다.
 * 주문·장바구니·공동구매·판매글 등록 같은 변경 요청을 한곳에서 막는다.
 *
 * <p>탈퇴 대기 여부는 토큰의 wp 표시로만 판단한다 (서버 메모리·DB 조회 없음 → 모든 서비스에서 같은 결과).
 * 회원 서비스가 탈퇴 신청·취소 응답과 로그인·재발급 때 그 시점 상태로 새 토큰을 준다.
 */
public class WithdrawalPendingFilter extends OncePerRequestFilter {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    // 탈퇴 대기 중에도 허용: 탈퇴 취소·환급 신청, 로그인·로그아웃·토큰 재발급
    private static final List<String> ALLOWED_PATHS = List.of("/api/members/me/withdrawal", "/api/auth/");

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
        return authentication != null
                && authentication.getPrincipal() instanceof SecurityUser user
                && user.withdrawalPending();
    }
}