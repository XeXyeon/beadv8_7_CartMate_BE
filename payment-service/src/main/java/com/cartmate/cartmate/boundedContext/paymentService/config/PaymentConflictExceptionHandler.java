package com.cartmate.cartmate.boundedContext.paymentService.config;

import com.cartmate.cartmate.boundedContext.paymentService.payment.exception.PaymentErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.rsData.RsData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 같은 지갑에 요청이 동시에 들어와 생기는 충돌을 500 대신 409(PAYMENT_CONFLICT)로 돌려준다.
 *
 * <p>ConcurrencyFailureException 하나로 두 경우를 잡는다.
 * <ul>
 *   <li>낙관적 락 실패(OptimisticLockingFailureException): 지갑 @Version이 먼저 커밋한 요청 때문에 맞지 않음</li>
 *   <li>데드락(CannotAcquireLockException): 홀딩 · 거래 내역 INSERT의 외래 키 검사가 지갑 행에 공유 락을 잡고,
 *       두 요청이 서로의 공유 락 때문에 지갑 UPDATE(배타 락)를 못 해 MySQL이 하나를 실패시킴</li>
 * </ul>
 * 두 경우 모두 트랜잭션 전체가 롤백되어 잔액은 그대로다. 그래서 "확실한 실패"이고 다시 시도하면 된다.
 *
 * <p>남은 과제: 이 처리는 응답 코드만 바꾼다. 사용자는 여전히 다시 시도해야 한다.
 * 근본 수정은 지갑 잔액을 조건부 UPDATE(WalletRepository.decreaseBalance · increaseBalance, 출금이 쓰는 방식)로
 * 먼저 바꿔 같은 지갑 요청이 순서대로 처리되게 하는 것이다(결제 차감 WalletPaymentUseCase.use, 환불 Wallet.refund).
 * 그러면 낙관적 락 실패와 외래 키 데드락이 생기지 않는다.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PaymentConflictExceptionHandler {

    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<RsData<Void>> handle(ConcurrencyFailureException e) {
        log.atWarn().addKeyValue("event", "payment.conflict")
                .addKeyValue("exception", e.getClass().getSimpleName())
                .setCause(e)
                .log("같은 지갑 요청 충돌로 롤백, 409로 응답");
        RsData<Void> rsData = new ServiceException(PaymentErrorCode.PAYMENT_CONFLICT).getRsData();
        return ResponseEntity.status(rsData.getStatusCode()).body(rsData);
    }
}
