package com.cartmate.cartmate.boundedContext.orderService.order.app.groupshared;

import com.cartmate.cartmate.boundedContext.orderService.order.exception.OrderErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.http.InternalApiClient.InternalServerUnavailableException;
import com.cartmate.cartmate.shared.coupon.dto.CouponQuote;
import com.cartmate.cartmate.shared.payment.dto.PaymentResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

// 분담금 쿠폰: 쿠폰 기록·사용은 결제 트랜잭션 전에, 결제가 실패로 끝나면 복구 요청은 따로 커밋한다
class SharedGroupOrderFacadeCouponTest {

    private static final Long ORDER_ID = 100L;
    private static final Long MEMBER = 2L;
    private static final Long COUPON = 9L;
    private static final CouponQuote QUOTE =
            new CouponQuote(COUPON, 90L, "전체 5천원", 5_000L, true, null, "FIXED", 5_000L, null, 0L);
    private static final Clock CLOCK = Clock.fixed(
            LocalDateTime.of(2026, 11, 20, 12, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

    private final SharedGroupOrderPaymentUseCase paymentUseCase = mock(SharedGroupOrderPaymentUseCase.class);
    private final SharedGroupOrderFacade facade = new SharedGroupOrderFacade(
            mock(SharedGroupOrderCreateUseCase.class), mock(SharedGroupOrderQueryUseCase.class), paymentUseCase,
            mock(SharedGroupOrderCancelUseCase.class), CLOCK, TransactionOperations.withoutTransaction());

    @Test
    @DisplayName("쿠폰을 기록하고 사용 처리한 뒤 그 쿠폰으로 결제한다")
    void recordsAndUsesCouponBeforePayment() {
        given(paymentUseCase.useCoupon(ORDER_ID, MEMBER, COUPON)).willReturn(QUOTE);
        given(paymentUseCase.pay(eq(ORDER_ID), eq(MEMBER), any(), eq(QUOTE), any())).willReturn(mock(PaymentResult.class));

        facade.pay(MEMBER, ORDER_ID, null, COUPON);

        var order = inOrder(paymentUseCase);
        order.verify(paymentUseCase).recordCoupon(ORDER_ID, MEMBER, COUPON);
        order.verify(paymentUseCase).useCoupon(ORDER_ID, MEMBER, COUPON);
        order.verify(paymentUseCase).pay(eq(ORDER_ID), eq(MEMBER), any(), eq(QUOTE), any());
        then(paymentUseCase).should(never()).releaseCoupon(any(), any());
    }

    @Test
    @DisplayName("쿠폰 사용이 실패하면(거절·응답 없음) 결제하지 않고 쿠폰 복구를 요청한다")
    void couponUseFailureReleasesWithoutPayment() {
        InternalServerUnavailableException timeout = new InternalServerUnavailableException("PRODUCT", "상품", null);
        given(paymentUseCase.useCoupon(ORDER_ID, MEMBER, COUPON)).willThrow(timeout);

        assertThatThrownBy(() -> facade.pay(MEMBER, ORDER_ID, null, COUPON)).isSameAs(timeout);
        then(paymentUseCase).should().releaseCoupon(ORDER_ID, MEMBER);
        then(paymentUseCase).should(never()).pay(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("결제가 업무 거절로 끝나면 쿠폰 복구를 요청하고 원래 오류를 던진다")
    void paymentRejectedReleasesCoupon() {
        ServiceException insufficient = new ServiceException("400-INSUFFICIENT_BALANCE", "예치금이 부족합니다.");
        given(paymentUseCase.useCoupon(ORDER_ID, MEMBER, COUPON)).willReturn(QUOTE);
        given(paymentUseCase.pay(any(), any(), any(), any(), any())).willThrow(insufficient);
        org.mockito.BDDMockito.willThrow(new IllegalStateException("outbox 저장 실패"))
                .given(paymentUseCase).releaseCoupon(ORDER_ID, MEMBER);

        assertThatThrownBy(() -> facade.pay(MEMBER, ORDER_ID, null, COUPON)).isSameAs(insufficient);
        then(paymentUseCase).should().releaseCoupon(ORDER_ID, MEMBER);
    }

    @Test
    @DisplayName("결제 결과를 모르거나(503) 팀 배송지만 빠졌으면(차감이 남음) 쿠폰 기록을 그대로 둔다")
    void unknownOrAddressMissingKeepsCoupon() {
        given(paymentUseCase.useCoupon(ORDER_ID, MEMBER, COUPON)).willReturn(QUOTE);
        given(paymentUseCase.pay(any(), any(), any(), any(), any()))
                .willThrow(new InternalServerUnavailableException("PAYMENT", "결제", null))
                .willThrow(new ServiceException(OrderErrorCode.ORDER_TEAM_ADDRESS_REQUIRED));

        assertThatThrownBy(() -> facade.pay(MEMBER, ORDER_ID, null, COUPON))
                .isInstanceOf(InternalServerUnavailableException.class);
        assertThatThrownBy(() -> facade.pay(MEMBER, ORDER_ID, null, COUPON))
                .hasMessageContaining(OrderErrorCode.ORDER_TEAM_ADDRESS_REQUIRED.getMsg());
        then(paymentUseCase).should(never()).releaseCoupon(any(), any());
    }

    @Test
    @DisplayName("요청에 쿠폰이 없어도 결과를 모르는 앞 시도가 기록한 쿠폰이 있으면 그 쿠폰으로 이어서 결제한다")
    void retryWithoutCouponUsesRecordedCoupon() {
        given(paymentUseCase.recordedCoupon(ORDER_ID, MEMBER)).willReturn(COUPON);
        given(paymentUseCase.useCoupon(ORDER_ID, MEMBER, COUPON)).willReturn(QUOTE);
        given(paymentUseCase.pay(eq(ORDER_ID), eq(MEMBER), any(), eq(QUOTE), any())).willReturn(mock(PaymentResult.class));

        facade.pay(MEMBER, ORDER_ID, null, null);

        then(paymentUseCase).should().pay(eq(ORDER_ID), eq(MEMBER), any(), eq(QUOTE), any());
    }
}
