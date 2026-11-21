package com.cartmate.cartmate.boundedContext.paymentService.payment.app;

import com.cartmate.cartmate.boundedContext.paymentService.payment.domain.Payment;
import com.cartmate.cartmate.boundedContext.paymentService.payment.domain.PaymentType;
import com.cartmate.cartmate.boundedContext.paymentService.payment.exception.PaymentErrorCode;
import com.cartmate.cartmate.boundedContext.paymentService.payment.out.PaymentRepository;
import com.cartmate.cartmate.boundedContext.paymentService.payment.out.PaymentWalletClient;
import com.cartmate.cartmate.global.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

// 예치금 차감은 (구분·주문·회원) 키로 한 번만 일어나야 한다. 응답을 못 받은 주문 서버가 같은 요청을 다시 보내도 두 번 빠지면 안 된다.
class PaymentChargeUseCaseTest {

    private static final PaymentType TYPE = PaymentType.INDIVIDUAL_GROUP;
    private static final Long ORDER_ID = 100L;
    private static final Long MEMBER_ID = 7L;
    private static final Long WALLET_ID = 70L;

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentWalletClient paymentWalletClient = mock(PaymentWalletClient.class);
    private final PaymentChargeUseCase useCase = new PaymentChargeUseCase(paymentRepository, paymentWalletClient);

    @Test
    @DisplayName("처음 온 차감은 결제를 저장한 뒤 그 결제 ID로 지갑에서 금액을 뺀다")
    void firstChargeSavesPaymentThenUsesWallet() {
        given(paymentRepository.findByTypeAndOrderIdAndMemberId(TYPE, ORDER_ID, MEMBER_ID)).willReturn(Optional.empty());
        given(paymentWalletClient.getWalletId(MEMBER_ID)).willReturn(WALLET_ID);
        given(paymentRepository.saveAndFlush(any(Payment.class))).willAnswer(invocation -> {
            Payment saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 500L);
            return saved;
        });

        Payment payment = useCase.charge(TYPE, ORDER_ID, MEMBER_ID, 13_500L);

        assertThat(payment.getId()).isEqualTo(500L);
        assertThat(payment.getAmount()).isEqualTo(13_500L);
        var order = inOrder(paymentRepository, paymentWalletClient);
        order.verify(paymentRepository).saveAndFlush(any(Payment.class));
        order.verify(paymentWalletClient).use(WALLET_ID, 13_500L, 500L, ORDER_ID, TYPE);
    }

    @Test
    @DisplayName("같은 키의 결제가 이미 있으면(재시도 · 응답 유실) 다시 빼지 않고 그 결제를 그대로 돌려준다")
    void duplicateChargeReturnsExistingWithoutUsingWallet() {
        Payment existing = payment(500L, 13_500L);
        given(paymentRepository.findByTypeAndOrderIdAndMemberId(TYPE, ORDER_ID, MEMBER_ID)).willReturn(Optional.of(existing));

        Payment payment = useCase.charge(TYPE, ORDER_ID, MEMBER_ID, 13_500L);

        assertThat(payment).isSameAs(existing);
        then(paymentRepository).should(never()).saveAndFlush(any());
        then(paymentWalletClient).should(never()).use(any(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("같은 키의 결제가 이미 전액 환불됐으면 다시 빼지 않고 거절한다")
    void canceledPaymentRejectsCharge() {
        Payment canceled = payment(500L, 13_500L);
        canceled.refund(13_500L);
        given(paymentRepository.findByTypeAndOrderIdAndMemberId(TYPE, ORDER_ID, MEMBER_ID)).willReturn(Optional.of(canceled));

        assertThatThrownBy(() -> useCase.charge(TYPE, ORDER_ID, MEMBER_ID, 13_500L))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(PaymentErrorCode.PAYMENT_ALREADY_CANCELED.getMsg());
        then(paymentWalletClient).should(never()).use(any(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("같은 키의 결제가 동시에 저장되면(유니크 제약) 지갑에서 빼지 않고 처리 중으로 거절한다")
    void concurrentSameKeyRejectsAsInProgress() {
        given(paymentRepository.findByTypeAndOrderIdAndMemberId(TYPE, ORDER_ID, MEMBER_ID)).willReturn(Optional.empty());
        given(paymentWalletClient.getWalletId(MEMBER_ID)).willReturn(WALLET_ID);
        given(paymentRepository.saveAndFlush(any(Payment.class)))
                .willThrow(new DataIntegrityViolationException("uk_payment_type_order_member"));

        assertThatThrownBy(() -> useCase.charge(TYPE, ORDER_ID, MEMBER_ID, 13_500L))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(PaymentErrorCode.PAYMENT_IN_PROGRESS.getMsg());
        then(paymentWalletClient).should(never()).use(any(), anyLong(), any(), any(), any());
    }

    private static Payment payment(Long id, long amount) {
        Payment payment = Payment.pay(ORDER_ID, MEMBER_ID, WALLET_ID, amount, TYPE);
        ReflectionTestUtils.setField(payment, "id", id);
        return payment;
    }
}
