package com.cartmate.cartmate.boundedContext.paymentService.wallet.out.transfer;

/**
 * 회원 계좌로 돈을 보내는 창구 (예치금 출금). custom.wallet.withdraw.transfer-mode로 구현을 고른다.
 *
 * <ul>
 *   <li>fake: 로컬·시연용. 바로 끝난다 ({@link FakeBankTransferClient})</li>
 *   <li>manual: 운영 기본. 구현이 없다 → 관리자가 직접 송금하고 완료·실패를 누른다</li>
 *   <li>(나중에) 오픈뱅킹 입금이체·지급대행: 이용기관 계약 후 구현 하나만 추가</li>
 * </ul>
 * 호출은 트랜잭션 밖에서 한다. 같은 transferId로 두 번 보내지 않는 것은 부르는 쪽(PENDING → PROCESSING 조건부 UPDATE)이 보장한다.
 */
public interface BankTransferClient {

    // 이체 요청. 응답을 못 받으면(시간 초과 등) UNKNOWN → 나중에 inquire로 확정
    TransferResult transfer(TransferCommand command);

    // 이체 결과 조회 (transferId 기준)
    TransferResult inquire(TransferCommand command);

    record TransferCommand(String transferId, String bankCode, String accountNumber, String accountHolder, long amount) {
    }

    enum Outcome { SUCCESS, FAILED, UNKNOWN }

    record TransferResult(Outcome outcome, String message) {

        public static TransferResult success(String message) {
            return new TransferResult(Outcome.SUCCESS, message);
        }

        public static TransferResult failed(String message) {
            return new TransferResult(Outcome.FAILED, message);
        }

        public static TransferResult unknown(String message) {
            return new TransferResult(Outcome.UNKNOWN, message);
        }
    }
}
