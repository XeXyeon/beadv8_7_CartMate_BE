package com.cartmate.cartmate.boundedContext.paymentService.wallet.app;

import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WalletWithdrawOrder;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WithdrawStatus;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.transfer.BankTransferClient;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.transfer.BankTransferClient.TransferCommand;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.transfer.BankTransferClient.TransferResult;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 출금 이체 (트랜잭션 밖, 외부 호출). 자동 이체 구현({@link BankTransferClient})이 있을 때만 동작한다 — manual 모드면 아무것도 하지 않는다.
 *
 * <ol>
 *   <li>PENDING → PROCESSING 조건부 UPDATE에서 이긴 요청만 이체를 보낸다 (신청 직후 스레드와 스케줄러가 겹쳐도 한 번)</li>
 *   <li>성공 → COMPLETED, 실패 → FAILED + 잔액 복구, 모름(시간 초과·예외) → PROCESSING 그대로</li>
 *   <li>모르는 건은 스케줄러가 결과를 조회해 확정한다. {@link #REVIEW_AFTER} 넘게 모르면 관리자 확인으로 넘긴다
 *       — 돈이 나갔을 수 있으므로 자동으로 실패 처리하지 않는다</li>
 * </ol>
 */
@Slf4j
@Component
public class WalletWithdrawTransferProcessor {

    static final Duration REVIEW_AFTER = Duration.ofMinutes(30);

    private final WalletFacade walletFacade;
    private final ObjectProvider<BankTransferClient> transferClientProvider;
    private final WithdrawAccountCipher cipher;
    private final Executor executor;
    private final Supplier<LocalDateTime> clock;

    // 맥락 전파 래퍼: 이체 스레드에서도 요청의 traceId가 로그에 이어진다
    @Autowired
    public WalletWithdrawTransferProcessor(WalletFacade walletFacade, ObjectProvider<BankTransferClient> transferClientProvider,
                                           WithdrawAccountCipher cipher) {
        this(walletFacade, transferClientProvider, cipher,
                ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor(), ContextSnapshotFactory.builder().build()),
                LocalDateTime::now);
    }

    // 테스트에서 바로 실행하는 executor와 고정 시각을 넣기 위한 생성자
    WalletWithdrawTransferProcessor(WalletFacade walletFacade, ObjectProvider<BankTransferClient> transferClientProvider,
                                    WithdrawAccountCipher cipher, Executor executor, Supplier<LocalDateTime> clock) {
        this.walletFacade = walletFacade;
        this.transferClientProvider = transferClientProvider;
        this.cipher = cipher;
        this.executor = executor;
        this.clock = clock;
    }

    public boolean isAutomatic() {
        return transferClientProvider.getIfAvailable() != null;
    }

    // 신청이 커밋된 뒤 부른다: 다른 스레드에서 이체 (응답을 기다리지 않음). 놓쳐도 스케줄러가 1분 뒤 다시 시작한다
    public void transferAsync(Long withdrawOrderId) {
        if (!isAutomatic()) {
            return;
        }
        executor.execute(() -> {
            try {
                transfer(withdrawOrderId);
            } catch (RuntimeException e) {
                log.atWarn().addKeyValue("event", "wallet.withdraw.transfer").addKeyValue("withdrawOrderId", withdrawOrderId)
                        .setCause(e).log("출금 이체 시작 실패 → 스케줄러가 다시 처리: withdrawOrderId={}", withdrawOrderId);
            }
        });
    }

    // PENDING인 건 이체. 이미 다른 요청이 시작했으면 아무것도 하지 않는다
    public void transfer(Long withdrawOrderId) {
        BankTransferClient client = transferClientProvider.getIfAvailable();
        if (client == null || !walletFacade.startWithdrawProcessing(withdrawOrderId, null, clock.get())) {
            return;
        }
        WalletWithdrawOrder order = walletFacade.getWithdrawOrder(withdrawOrderId);
        TransferResult result = call(() -> client.transfer(command(order)), withdrawOrderId);
        apply(withdrawOrderId, result);
    }

    // PROCESSING인 건의 결과 확인 (스케줄러)
    public void confirm(Long withdrawOrderId) {
        BankTransferClient client = transferClientProvider.getIfAvailable();
        if (client == null) {
            return;
        }
        WalletWithdrawOrder order = walletFacade.getWithdrawOrder(withdrawOrderId);
        if (order.getStatus() != WithdrawStatus.PROCESSING || order.getReviewRequestedAt() != null) {
            return;
        }
        TransferResult result = call(() -> client.inquire(command(order)), withdrawOrderId);
        LocalDateTime now = clock.get();
        if (result.outcome() == BankTransferClient.Outcome.UNKNOWN
                && !order.getProcessingStartedAt().plus(REVIEW_AFTER).isAfter(now)) {
            if (walletFacade.requestWithdrawReview(withdrawOrderId, now)) {
                log.atError().addKeyValue("event", "wallet.withdraw.review").addKeyValue("withdrawOrderId", withdrawOrderId)
                        .log("출금 이체 결과를 {}분 넘게 확인하지 못함 → 관리자 확인 필요: withdrawOrderId={}",
                                REVIEW_AFTER.toMinutes(), withdrawOrderId);
            }
            return;
        }
        apply(withdrawOrderId, result);
    }

    private void apply(Long withdrawOrderId, TransferResult result) {
        LocalDateTime now = clock.get();
        switch (result.outcome()) {
            case SUCCESS -> walletFacade.completeWithdraw(withdrawOrderId, result.message(), null, now);
            case FAILED -> walletFacade.failWithdraw(withdrawOrderId, result.message(), null, now);
            case UNKNOWN -> log.atInfo().addKeyValue("event", "wallet.withdraw.transfer").addKeyValue("result", "UNKNOWN")
                    .addKeyValue("withdrawOrderId", withdrawOrderId)
                    .log("출금 이체 결과 모름 → 스케줄러가 조회해 확정: withdrawOrderId={}", withdrawOrderId);
        }
    }

    // 이체 API 예외는 "모름"으로 본다 (요청이 은행에 닿았을 수 있다)
    private static TransferResult call(Supplier<TransferResult> call, Long withdrawOrderId) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.atWarn().addKeyValue("event", "wallet.withdraw.transfer").addKeyValue("withdrawOrderId", withdrawOrderId)
                    .setCause(e).log("출금 이체 호출 오류 → 결과 모름으로 처리: withdrawOrderId={}", withdrawOrderId);
            return TransferResult.unknown("이체 호출 오류");
        }
    }

    private TransferCommand command(WalletWithdrawOrder order) {
        return new TransferCommand(order.getTransferId(), order.getBankCode(),
                cipher.decrypt(order.getAccountNumberEncrypted()), order.getAccountHolder(), order.getAmount());
    }
}
