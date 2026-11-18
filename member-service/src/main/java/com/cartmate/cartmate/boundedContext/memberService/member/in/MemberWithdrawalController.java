package com.cartmate.cartmate.boundedContext.memberService.member.in;

import com.cartmate.cartmate.boundedContext.memberService.member.app.MemberFacade;
import com.cartmate.cartmate.boundedContext.memberService.member.dto.WithdrawalCheckResponse;
import com.cartmate.cartmate.boundedContext.memberService.member.dto.WithdrawalRequest;
import com.cartmate.cartmate.global.rsData.RsData;
import com.cartmate.cartmate.global.security.AuthCookieManager;
import com.cartmate.cartmate.global.security.SecurityUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

// 회원 탈퇴. 탈퇴 대기 중에도 이 주소는 WithdrawalPendingFilter가 허용한다.
// 신청·취소하면 탈퇴 대기 표시(wp)가 바뀐 새 Access Token을 쿠키로 준다 (판매자 등록과 같은 방식)
@RestController
@RequestMapping("/api/members/me/withdrawal")
@RequiredArgsConstructor
public class MemberWithdrawalController {

    private final MemberFacade memberFacade;
    private final AuthCookieManager authCookieManager;

    @GetMapping
    @Operation(operationId = "checkWithdrawal", summary = "탈퇴 가능 여부 (막는 이유, 대기 중이면 확정 예정일)")
    public RsData<WithdrawalCheckResponse> check(@AuthenticationPrincipal SecurityUser user) {
        return new RsData<>("200-1", "탈퇴 가능 여부 조회 성공", memberFacade.checkWithdrawal(user.memberId()));
    }

    @PostMapping
    @Operation(operationId = "requestWithdrawal", summary = "탈퇴 신청 (비밀번호 확인, 30일 유예)")
    public RsData<WithdrawalCheckResponse> request(@AuthenticationPrincipal SecurityUser user,
                                                   @Valid @RequestBody WithdrawalRequest request,
                                                   HttpServletResponse response) {
        MemberFacade.WithdrawalResult result = memberFacade.requestWithdrawal(user.memberId(), request.password(), user.amr());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieManager.accessTokenCookie(result.accessToken()).toString());
        return new RsData<>("200-1", "탈퇴를 신청했습니다. 30일 뒤 탈퇴가 확정됩니다.", result.check());
    }

    @DeleteMapping
    @Operation(operationId = "cancelWithdrawal", summary = "탈퇴 취소 (대기 중일 때만)")
    public RsData<WithdrawalCheckResponse> cancel(@AuthenticationPrincipal SecurityUser user,
                                                  HttpServletResponse response) {
        MemberFacade.WithdrawalResult result = memberFacade.cancelWithdrawal(user.memberId(), user.amr());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieManager.accessTokenCookie(result.accessToken()).toString());
        return new RsData<>("200-1", "탈퇴를 취소했습니다.", result.check());
    }
}
