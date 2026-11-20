package com.cartmate.cartmate.boundedContext.paymentService.wallet.app;

import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.dto.AdminWithdrawAccountResponse;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.dto.WalletWithdrawOrderResponse;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WalletWithdrawOrder;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WalletWithdrawOrder.WithdrawAccount;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WithdrawStatus;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.exception.WalletErrorCode;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.PayoutAccountClient;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.PayoutAccountClient.PayoutAccount;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.WalletWithdrawOrderRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;

/**
 * 예치금 출금 흐름 (트랜잭션 없음 — 회원 서버 호출·이체가 DB 연결을 잡지 않게). DB 처리는 WalletFacade가 트랜잭션을 연다.
 *
 * <p>회원: 신청 → (자동 이체면) 커밋 뒤 다른 스레드에서 이체 / 대기 중이면 취소.
 * <p>관리자(manual 모드): 송금 시작 → 직접 송금 → 완료 또는 실패. 결과를 오래 모르는 자동 이체 건도 관리자가 완료·실패로 확정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletWithdrawService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] TRANSFER_ID_CHARS = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private final WalletFacade walletFacade;
    private final PayoutAccountClient payoutAccountClient;
    private final WithdrawAccountCipher cipher;
    private final WalletWithdrawTransferProcessor transferProcessor;
    private final WalletWithdrawOrderRepository walletWithdrawOrderRepository;

    public WalletWithdrawOrderResponse request(Long memberId, Long amount, String idempotencyKey, LocalDateTime now) {
        Long walletId = walletFacade.getWallet(memberId).walletId();

        // 트랜잭션 밖: 회원 서버에서 출금 계좌를 받아 결제 서버 키로 잠근다 (신청 뒤 계좌를 바꿔도 이 건은 이 계좌로)
        PayoutAccount payoutAccount = payoutAccountClient.find(memberId)
                .orElseThrow(() -> new ServiceException(WalletErrorCode.WALLET_PAYOUT_ACCOUNT_REQUIRED));
        WithdrawAccount account = new WithdrawAccount(payoutAccount.bankCode(), payoutAccount.bankName(),
                payoutAccount.accountHolder(), cipher.encrypt(payoutAccount.accountNumber()),
                WithdrawAccountCipher.mask(payoutAccount.accountNumber()));

        WalletWithdrawOrderResponse order;
        try {
            order = walletFacade.requestWithdraw(walletId, amount, idempotencyKey, newTransferId(), account, now);
        } catch (DataIntegrityViolationException e) {
            // 같은 키로 동시에 와서 한쪽이 유니크 제약에 걸렸다 → 새 트랜잭션에서 먼저 만든 건을 검증해 돌려준다
            if (!walletWithdrawOrderRepository.existsByIdempotencyKey(idempotencyKey)) {
                throw e;
            }
            order = walletFacade.requestWithdraw(walletId, amount, idempotencyKey, newTransferId(), account, now);
        }

        if (order.status() == WithdrawStatus.PENDING) {
            transferProcessor.transferAsync(order.withdrawOrderId());
        }
        return order;
    }

    public void cancel(Long memberId, Long withdrawOrderId, LocalDateTime now) {
        Long walletId = walletFacade.getWallet(memberId).walletId();
        walletFacade.cancelWithdraw(walletId, withdrawOrderId, now);
    }

    // ===== 관리자 =====

    // 송금 시작 (manual 모드). 이 뒤로는 회원이 취소할 수 없다
    public void startByAdmin(Long adminId, Long withdrawOrderId, LocalDateTime now) {
        if (transferProcessor.isAutomatic()) {
            throw new ServiceException(WalletErrorCode.WALLET_WITHDRAW_AUTO_TRANSFER);
        }
        requireAccount(walletFacade.getWithdrawOrder(withdrawOrderId));
        if (!walletFacade.startWithdrawProcessing(withdrawOrderId, adminId, now)) {
            throw new ServiceException(WalletErrorCode.WALLET_WITHDRAW_NOT_STARTABLE);
        }
        log.atInfo().addKeyValue("event", "wallet.withdraw.admin").addKeyValue("action", "start")
                .addKeyValue("withdrawOrderId", withdrawOrderId).addKeyValue("adminId", adminId)
                .log("관리자 출금 송금 시작: withdrawOrderId={}, adminId={}", withdrawOrderId, adminId);
    }

    public void completeByAdmin(Long adminId, Long withdrawOrderId, String memo, LocalDateTime now) {
        if (!walletFacade.completeWithdraw(withdrawOrderId, memo, adminId, now)) {
            throw new ServiceException(WalletErrorCode.WALLET_WITHDRAW_NOT_COMPLETABLE);
        }
        log.atInfo().addKeyValue("event", "wallet.withdraw.admin").addKeyValue("action", "complete")
                .addKeyValue("withdrawOrderId", withdrawOrderId).addKeyValue("adminId", adminId)
                .log("관리자 출금 완료: withdrawOrderId={}, adminId={}", withdrawOrderId, adminId);
    }

    public void failByAdmin(Long adminId, Long withdrawOrderId, String reason, LocalDateTime now) {
        if (!walletFacade.failWithdraw(withdrawOrderId, reason, adminId, now)) {
            throw new ServiceException(WalletErrorCode.WALLET_WITHDRAW_NOT_FAILABLE);
        }
        log.atInfo().addKeyValue("event", "wallet.withdraw.admin").addKeyValue("action", "fail")
                .addKeyValue("withdrawOrderId", withdrawOrderId).addKeyValue("adminId", adminId)
                .log("관리자 출금 실패 처리(잔액 복구): withdrawOrderId={}, adminId={}", withdrawOrderId, adminId);
    }

    // 계좌번호 원문 열람: 누가 언제 봤는지 남긴다 (계좌번호 자체는 로그에 남기지 않음)
    public AdminWithdrawAccountResponse revealAccount(Long adminId, Long withdrawOrderId) {
        WalletWithdrawOrder order = walletFacade.getWithdrawOrder(withdrawOrderId);
        requireAccount(order);
        log.atInfo().addKeyValue("event", "wallet.withdraw.account-viewed")
                .addKeyValue("withdrawOrderId", withdrawOrderId).addKeyValue("adminId", adminId)
                .log("관리자 출금 계좌 열람: withdrawOrderId={}, adminId={}", withdrawOrderId, adminId);
        return new AdminWithdrawAccountResponse(order.getId(), order.getBankName(), order.getAccountHolder(),
                cipher.decrypt(order.getAccountNumberEncrypted()), order.getAmount());
    }

    private static void requireAccount(WalletWithdrawOrder order) {
        if (order.getTransferId() == null || order.getAccountNumberEncrypted() == null) {
            throw new ServiceException(WalletErrorCode.WALLET_WITHDRAW_ACCOUNT_MISSING);
        }
    }

    // 20자: "W" + 19자 (오픈뱅킹 bank_tran_id 길이). 실제 연동 때는 이용기관 코드 형식에 맞춰 바꾼다
    static String newTransferId() {
        char[] id = new char[20];
        id[0] = 'W';
        for (int i = 1; i < id.length; i++) {
            id[i] = TRANSFER_ID_CHARS[RANDOM.nextInt(TRANSFER_ID_CHARS.length)];
        }
        return new String(id);
    }
}
