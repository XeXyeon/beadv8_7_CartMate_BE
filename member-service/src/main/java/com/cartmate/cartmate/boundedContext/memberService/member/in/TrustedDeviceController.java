package com.cartmate.cartmate.boundedContext.memberService.member.in;

import com.cartmate.cartmate.boundedContext.memberService.member.app.MemberFacade;
import com.cartmate.cartmate.boundedContext.memberService.member.dto.TrustedDeviceResponse;
import com.cartmate.cartmate.boundedContext.memberService.member.exception.MemberErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.rsData.RsData;
import com.cartmate.cartmate.global.security.SecurityUser;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// 신뢰 기기 ("이 기기 30일 기억") 관리. 기기 쿠키가 /api/auth에만 실리므로 여기 둔다 → "지금 이 기기" 표시·해제 시 쿠키 삭제.
// /api/auth/** 는 permitAll이라 로그인 여부를 직접 확인한다
@RestController
@RequestMapping("/api/auth/trusted-devices")
@RequiredArgsConstructor
public class TrustedDeviceController {
    private final MemberFacade memberFacade;
    private final TrustedDeviceCookie trustedDeviceCookie;

    @GetMapping
    public RsData<List<TrustedDeviceResponse>> trustedDevices(
            @AuthenticationPrincipal SecurityUser user,
            @CookieValue(name = TrustedDeviceCookie.NAME, required = false) String trustedDevice) {
        return new RsData<>("200-1", "신뢰 기기 목록입니다.",
                memberFacade.getTrustedDevices(requireLogin(user).memberId(), trustedDevice));
    }

    @DeleteMapping("/{deviceId}")
    public RsData<Void> revokeTrustedDevice(@AuthenticationPrincipal SecurityUser user, @PathVariable Long deviceId,
                                            @CookieValue(name = TrustedDeviceCookie.NAME, required = false) String trustedDevice,
                                            HttpServletResponse response) {
        if (memberFacade.revokeTrustedDevice(requireLogin(user).memberId(), deviceId, trustedDevice)) {
            response.addHeader(HttpHeaders.SET_COOKIE, trustedDeviceCookie.expired().toString());
        }
        return new RsData<>("200-1", "이 기기는 다음 로그인부터 인증번호를 다시 받습니다.");
    }

    @DeleteMapping
    public RsData<Void> revokeAllTrustedDevices(@AuthenticationPrincipal SecurityUser user, HttpServletResponse response) {
        memberFacade.revokeAllTrustedDevices(requireLogin(user).memberId());
        response.addHeader(HttpHeaders.SET_COOKIE, trustedDeviceCookie.expired().toString());
        return new RsData<>("200-1", "모든 기기에서 다음 로그인부터 인증번호를 다시 받습니다.");
    }

    private static SecurityUser requireLogin(SecurityUser user) {
        if (user == null) {
            throw new ServiceException(MemberErrorCode.AUTH_UNAUTHORIZED);
        }
        return user;
    }
}
