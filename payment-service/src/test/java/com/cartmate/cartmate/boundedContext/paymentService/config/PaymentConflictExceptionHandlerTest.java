package com.cartmate.cartmate.boundedContext.paymentService.config;

import com.cartmate.cartmate.boundedContext.paymentService.payment.exception.PaymentErrorCode;
import com.cartmate.cartmate.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 같은 지갑 동시 요청 충돌(낙관적 락 실패 · 데드락)이 500이 아니라 409 PAYMENT_CONFLICT로 나가는지 본다.
 * order는 이 코드를 받으면 결제 실패가 확실한 것으로 보고 재고 선점을 해제한다(PAYMENT_IN_PROGRESS와 다르게).
 */
class PaymentConflictExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new PaymentConflictExceptionHandler(), new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("낙관적 락 실패(지갑 version 불일치)는 409 PAYMENT_CONFLICT")
    void optimisticLockFailureIsConflict() throws Exception {
        mockMvc.perform(get("/optimistic"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.resultCode").value(PaymentErrorCode.PAYMENT_CONFLICT.resultCode()));
    }

    @Test
    @DisplayName("데드락(락 획득 실패)도 409 PAYMENT_CONFLICT")
    void deadlockIsConflict() throws Exception {
        mockMvc.perform(get("/deadlock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.resultCode").value(PaymentErrorCode.PAYMENT_CONFLICT.resultCode()));
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/optimistic")
        void optimistic() {
            throw new ObjectOptimisticLockingFailureException("Wallet", 1L);
        }

        @GetMapping("/deadlock")
        void deadlock() {
            throw new CannotAcquireLockException("Deadlock found when trying to get lock");
        }
    }
}
