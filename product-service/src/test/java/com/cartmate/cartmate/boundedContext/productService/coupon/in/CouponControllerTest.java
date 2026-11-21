package com.cartmate.cartmate.boundedContext.productService.coupon.in;

import com.cartmate.cartmate.boundedContext.productService.code.CouponErrorCode;
import com.cartmate.cartmate.boundedContext.productService.coupon.app.CouponFacade;
import com.cartmate.cartmate.global.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

// 비로그인으로 상품 상세의 쿠폰 받기 목록을 부르면 500이 났다(user.memberId()에서 NPE).
class CouponControllerTest {

    private final CouponFacade couponFacade = mock(CouponFacade.class);
    private final CouponController controller = new CouponController(couponFacade);

    @Test
    @DisplayName("비로그인도 받을 수 있는 쿠폰 목록을 본다(받음 여부는 회원 없이 계산)")
    void anonymousCanListDownloadable() {
        given(couponFacade.downloadable(null, 7L)).willReturn(List.of());

        assertThat(controller.downloadable(null, 7L).getResultCode()).isEqualTo("200-1");
        then(couponFacade).should().downloadable(null, 7L);
    }

    @Test
    @DisplayName("비로그인으로 쿠폰함 · 쿠폰 받기를 부르면 500이 아니라 로그인 필요(401)로 답한다")
    void anonymousMyCouponsAndDownloadRequireLogin() {
        assertThatThrownBy(() -> controller.myCoupons(null))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(CouponErrorCode.COUPON_LOGIN_REQUIRED.getMsg());
        assertThatThrownBy(() -> controller.download(null, 1L))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(CouponErrorCode.COUPON_LOGIN_REQUIRED.getMsg());
        then(couponFacade).should(never()).download(any(), any());
    }
}
