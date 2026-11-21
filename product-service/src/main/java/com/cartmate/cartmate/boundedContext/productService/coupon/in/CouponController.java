package com.cartmate.cartmate.boundedContext.productService.coupon.in;

import com.cartmate.cartmate.boundedContext.productService.code.CouponErrorCode;
import com.cartmate.cartmate.boundedContext.productService.coupon.app.CouponFacade;
import com.cartmate.cartmate.boundedContext.productService.coupon.dto.DownloadableCouponResponse;
import com.cartmate.cartmate.boundedContext.productService.coupon.dto.MemberCouponResponse;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.rsData.RsData;
import com.cartmate.cartmate.global.security.SecurityUser;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// 공통 보안 설정이 아직 /api/coupons/** 를 로그인 없이도 통과시키므로(anyRequest().permitAll()) 로그인 여부를 여기서 확인한다.
// 비로그인 사용자가 상품 상세 등에서 불러도 500이 나지 않게: 받을 수 있는 쿠폰은 비로그인도 보고(받음 여부는 모두 false),
// 쿠폰함 · 받기는 401로 답한다.
@RestController
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponFacade couponFacade;

    @GetMapping("/me")
    @Operation(operationId = "getMyCoupons", summary = "내 쿠폰함")
    public RsData<List<MemberCouponResponse>> myCoupons(@AuthenticationPrincipal SecurityUser user) {
        return new RsData<>("200-1", "쿠폰함 조회 성공", couponFacade.myCoupons(requireMemberId(user)));
    }

    @GetMapping("/downloadable")
    @Operation(operationId = "getDownloadableCoupons",
            summary = "받을 수 있는 쿠폰 (sellerId를 주면 전체 + 그 판매자 쿠폰). 비로그인이면 받음 여부는 모두 false")
    public RsData<List<DownloadableCouponResponse>> downloadable(@AuthenticationPrincipal SecurityUser user,
                                                                 @RequestParam(required = false) Long sellerId) {
        Long memberId = user == null ? null : user.memberId();
        return new RsData<>("200-1", "받을 수 있는 쿠폰 조회 성공", couponFacade.downloadable(memberId, sellerId));
    }

    @PostMapping("/{couponId}/download")
    @Operation(operationId = "downloadCoupon", summary = "쿠폰 받기")
    public RsData<Void> download(@AuthenticationPrincipal SecurityUser user, @PathVariable Long couponId) {
        couponFacade.download(requireMemberId(user), couponId);
        return new RsData<>("200-1", "쿠폰을 받았습니다.");
    }

    private static Long requireMemberId(SecurityUser user) {
        if (user == null) {
            throw new ServiceException(CouponErrorCode.COUPON_LOGIN_REQUIRED);
        }
        return user.memberId();
    }
}
