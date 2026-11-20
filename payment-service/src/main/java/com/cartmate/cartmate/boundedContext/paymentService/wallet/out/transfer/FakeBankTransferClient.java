package com.cartmate.cartmate.boundedContext.paymentService.wallet.out.transfer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 가짜 이체 (로컬·시연). 실제로 돈을 보내지 않는다. 계좌번호 끝자리로 결과를 정한다.
 * <ul>
 *   <li>1111 → 실패 (잔액이 돌아온다)</li>
 *   <li>2222 → 응답 없음, 조회하면 성공 (스케줄러가 1분 뒤 확정)</li>
 *   <li>3333 → 계속 모름 (30분 뒤 관리자 확인 대상)</li>
 *   <li>그 외 → 바로 성공</li>
 * </ul>
 * 회원 쪽 가짜 실명 확인은 0000(불일치)·9999(오류)만 막으므로 위 계좌번호로 등록할 수 있다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "custom.wallet.withdraw", name = "transfer-mode", havingValue = "fake", matchIfMissing = true)
public class FakeBankTransferClient implements BankTransferClient {

    public FakeBankTransferClient() {
        log.warn("[예치금 출금] fake 이체 모드입니다. 실제로 송금하지 않습니다.");
    }

    @Override
    public TransferResult transfer(TransferCommand command) {
        String account = command.accountNumber();
        if (account.endsWith("1111")) {
            return TransferResult.failed("수취 계좌 오류 (가짜 이체)");
        }
        if (account.endsWith("2222") || account.endsWith("3333")) {
            return TransferResult.unknown("응답 시간 초과 (가짜 이체)");
        }
        return TransferResult.success("가짜 이체 완료");
    }

    @Override
    public TransferResult inquire(TransferCommand command) {
        if (command.accountNumber().endsWith("3333")) {
            return TransferResult.unknown("결과 확인 불가 (가짜 이체)");
        }
        return transfer(command).outcome() == Outcome.FAILED
                ? TransferResult.failed("수취 계좌 오류 (가짜 이체)")
                : TransferResult.success("가짜 이체 완료 (조회로 확인)");
    }
}
