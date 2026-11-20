package com.cartmate.cartmate.boundedContext.paymentService.wallet.app;

import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.dto.WalletWithdrawOrderResponse;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.Wallet;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WalletTransaction;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WalletTransactionType;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WalletWithdrawOrder;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WalletWithdrawOrder.WithdrawAccount;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WithdrawStatus;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.WalletRepository;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.WalletTransactionRepository;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.WalletWithdrawOrderRepository;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.transfer.BankTransferClient;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.transfer.FakeBankTransferClient;
import com.cartmate.cartmate.global.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

// 예치금 출금: 돈(잔액이 음수가 되지 않음, 복구는 한 번), 상태 경쟁(취소 vs 송금 시작), 결과 모름은 자동 실패 금지
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop", // 결제 서버 설정에는 테이블 생성이 없다
        "custom.wallet.withdraw.account-encryption-key=Y2FydG1hdGUtbG9jYWwtd2FsbGV0LWFjY3Qta2V5ISE="
})
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 실제 커밋으로 별도 트랜잭션·동시성을 확인한다
@Import({WalletFacade.class, WalletWithdrawUseCase.class, WalletWithdrawQueryUseCase.class, WalletQueryUseCase.class,
        WalletEnsureUseCase.class, WalletCreateUseCase.class, LedgerRecorder.class, WithdrawAccountCipher.class,
        WalletWithdrawFlowTest.Auditing.class})
class WalletWithdrawFlowTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @TestConfiguration
    @EnableJpaAuditing
    static class Auditing {
    }

    // 이 테스트와 상관없는 WalletFacade 의존성
    @MockitoBean WalletChargeUseCase walletChargeUseCase;
    @MockitoBean WalletPaymentUseCase walletPaymentUseCase;
    @MockitoBean WalletSettlementUseCase walletSettlementUseCase;

    @Autowired WalletFacade walletFacade;
    @Autowired WithdrawAccountCipher cipher;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletWithdrawOrderRepository withdrawOrderRepository;
    @Autowired WalletTransactionRepository walletTransactionRepository;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 10, 0);

    @AfterEach
    void clean() {
        walletTransactionRepository.deleteAllInBatch();
        withdrawOrderRepository.deleteAllInBatch();
        walletRepository.deleteAllInBatch();
    }

    @Test
    void 동시에_여러_건을_신청해도_잔액이_음수가_되지_않고_가능한_만큼만_빠진다() throws Exception {
        Long walletId = walletWithBalance(1L, 10_000L);

        List<Boolean> results = concurrently(5, i -> () -> {
            try {
                request(walletId, 3_000L, "key-" + i, "1234567890");
                return true;
            } catch (ServiceException e) {
                return false; // 잔액 부족
            }
        });

        assertThat(results).filteredOn(ok -> ok).hasSize(3);
        assertThat(balance(walletId)).isEqualTo(1_000L);
        assertThat(ledger(WalletTransactionType.WITHDRAW)).hasSize(3);
    }

    @Test
    void 같은_멱등_키로_다시_신청하면_처음_건을_돌려주고_잔액은_한_번만_빠진다() {
        Long walletId = walletWithBalance(2L, 10_000L);

        WalletWithdrawOrderResponse first = request(walletId, 4_000L, "same-key", "1234567890");
        WalletWithdrawOrderResponse again = request(walletId, 4_000L, "same-key", "1234567890");

        assertThat(again.withdrawOrderId()).isEqualTo(first.withdrawOrderId());
        assertThat(balance(walletId)).isEqualTo(6_000L);
    }

    @Test
    void 실패_처리를_두_번_해도_잔액은_한_번만_돌아온다() {
        Long walletId = walletWithBalance(3L, 10_000L);
        Long orderId = request(walletId, 4_000L, "fail-key", "1234567890").withdrawOrderId();

        assertThat(walletFacade.failWithdraw(orderId, "계좌 오류", 99L, NOW)).isTrue();
        assertThat(walletFacade.failWithdraw(orderId, "계좌 오류", 99L, NOW)).isFalse();

        assertThat(balance(walletId)).isEqualTo(10_000L);
        assertThat(ledger(WalletTransactionType.WITHDRAW_RESTORE)).hasSize(1);
    }

    @Test
    void 회원_취소와_송금_시작이_동시에_오면_하나만_성공한다() throws Exception {
        for (int round = 0; round < 5; round++) {
            Long walletId = walletWithBalance(100L + round, 10_000L);
            Long orderId = request(walletId, 4_000L, "race-" + round, "1234567890").withdrawOrderId();

            AtomicReference<Boolean> canceled = new AtomicReference<>(false);
            List<Boolean> results = concurrently(2, i -> () -> {
                if (i == 0) {
                    return walletFacade.startWithdrawProcessing(orderId, 99L, NOW);
                }
                try {
                    walletFacade.cancelWithdraw(walletId, orderId, NOW);
                    canceled.set(true);
                    return true;
                } catch (ServiceException e) {
                    return false; // 이미 송금 시작
                }
            });

            assertThat(results).filteredOn(ok -> ok).hasSize(1);
            WalletWithdrawOrder order = withdrawOrderRepository.findById(orderId).orElseThrow();
            if (canceled.get()) {
                assertThat(order.getStatus()).isEqualTo(WithdrawStatus.CANCELED);
                assertThat(balance(walletId)).isEqualTo(10_000L);
            } else {
                assertThat(order.getStatus()).isEqualTo(WithdrawStatus.PROCESSING);
                assertThat(balance(walletId)).isEqualTo(6_000L);
            }
        }
    }

    @Test
    void 이체_결과를_오래_모르면_자동으로_실패시키지_않고_관리자_확인으로_넘긴다() {
        Long walletId = walletWithBalance(4L, 10_000L);
        Long orderId = request(walletId, 4_000L, "unknown-key", "1234563333").withdrawOrderId(); // 가짜 이체: 계속 모름

        AtomicReference<LocalDateTime> clock = new AtomicReference<>(NOW);
        WalletWithdrawTransferProcessor processor = processor(clock);

        processor.transfer(orderId);
        clock.set(NOW.plusMinutes(5));
        processor.confirm(orderId); // 아직 30분 전: 그대로
        assertThat(withdrawOrderRepository.findById(orderId).orElseThrow().getReviewRequestedAt()).isNull();

        clock.set(NOW.plusMinutes(31));
        processor.confirm(orderId);

        WalletWithdrawOrder order = withdrawOrderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(WithdrawStatus.PROCESSING);
        assertThat(order.getReviewRequestedAt()).isNotNull();
        assertThat(balance(walletId)).isEqualTo(6_000L); // 돈이 나갔을 수 있으므로 돌려주지 않는다
    }

    @Test
    void 이체가_실패하면_잔액이_돌아오고_성공하면_완료된다() {
        Long walletId = walletWithBalance(5L, 10_000L);
        Long failing = request(walletId, 1_000L, "transfer-fail", "1234561111").withdrawOrderId(); // 가짜 이체: 실패
        Long passing = request(walletId, 2_000L, "transfer-ok", "1234567890").withdrawOrderId();

        WalletWithdrawTransferProcessor processor = processor(new AtomicReference<>(NOW));
        processor.transfer(failing);
        processor.transfer(passing);
        processor.transfer(passing); // 이미 처리됨: 다시 보내지 않는다

        assertThat(withdrawOrderRepository.findById(failing).orElseThrow().getStatus()).isEqualTo(WithdrawStatus.FAILED);
        assertThat(withdrawOrderRepository.findById(passing).orElseThrow().getStatus()).isEqualTo(WithdrawStatus.COMPLETED);
        assertThat(balance(walletId)).isEqualTo(8_000L);
    }

    @Test
    void 예전_방식_출금_건은_취소해도_잔액을_돌려주지_않는다() {
        // 2026-11 이전: 신청 때 잔액을 빼지 않았고 거래 번호·계좌가 없다
        Long walletId = walletWithBalance(6L, 10_000L);
        Wallet wallet = walletRepository.findById(walletId).orElseThrow();
        Long legacyId = withdrawOrderRepository.save(WalletWithdrawOrder.request(wallet, "legacy", 3_000L, null,
                new WithdrawAccount(null, null, null, null, null))).getId();

        walletFacade.cancelWithdraw(walletId, legacyId, NOW);

        assertThat(balance(walletId)).isEqualTo(10_000L);
        assertThat(walletFacade.getWithdrawingAmount(6L)).isZero();
    }

    private WalletWithdrawTransferProcessor processor(AtomicReference<LocalDateTime> clock) {
        @SuppressWarnings("unchecked")
        ObjectProvider<BankTransferClient> provider = mock(ObjectProvider.class);
        given(provider.getIfAvailable()).willReturn(new FakeBankTransferClient());
        return new WalletWithdrawTransferProcessor(walletFacade, provider, cipher, Runnable::run, clock::get);
    }

    private WalletWithdrawOrderResponse request(Long walletId, Long amount, String key, String accountNumber) {
        WithdrawAccount account = new WithdrawAccount("004", "국민은행", "회원", cipher.encrypt(accountNumber),
                WithdrawAccountCipher.mask(accountNumber));
        return walletFacade.requestWithdraw(walletId, amount, key, WalletWithdrawService.newTransferId(), account, NOW);
    }

    private Long walletWithBalance(Long memberId, Long balance) {
        Wallet wallet = Wallet.createWallet(memberId);
        wallet.charge(balance);
        return walletRepository.save(wallet).getId();
    }

    private long balance(Long walletId) {
        return walletRepository.findById(walletId).orElseThrow().getBalance();
    }

    private List<WalletTransaction> ledger(WalletTransactionType type) {
        return walletTransactionRepository.findAll().stream().filter(t -> t.getType() == type).toList();
    }

    private interface TaskFactory {
        Callable<Boolean> create(int index);
    }

    private static List<Boolean> concurrently(int threads, TaskFactory factory) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> futures = IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return factory.create(i).call();
                    }))
                    .toList();
            start.countDown();
            List<Boolean> results = new java.util.ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
