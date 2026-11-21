package com.cartmate.cartmate.boundedContext.paymentService.wallet.app;

import com.cartmate.cartmate.boundedContext.paymentService.wallet.app.dto.WalletBalanceResponse;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.Holding;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.Wallet;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.HoldingRepository;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.HoldingSettlementRepository;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.WalletRepository;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.WalletTransactionRepository;
import com.cartmate.cartmate.shared.wallet.WalletPurchaseType;
import com.cartmate.cartmate.shared.wallet.dto.SettleAndDepositRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

// 가입 이벤트가 처리되지 않아 지갑이 없는 회원: 필요한 곳에서 지갑을 만든다 (조회 = 0원 지갑, 동시에 와도 하나, 판매자 정산도 입금)
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop") // 결제 서버 설정에는 테이블 생성이 없다
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 실제 커밋으로 별도 트랜잭션·동시성을 확인한다
@Import({WalletFacade.class, WalletCreateUseCase.class, WalletEnsureUseCase.class, WalletQueryUseCase.class,
        WalletSettlementUseCase.class, LedgerRecorder.class, WalletGetOrCreateTest.Auditing.class})
class WalletGetOrCreateTest {

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
    @MockitoBean WalletWithdrawUseCase walletWithdrawUseCase;
    @MockitoBean WalletWithdrawQueryUseCase walletWithdrawQueryUseCase;
    @MockitoBean PlatformWalletProvider platformWalletProvider;

    @Autowired WalletFacade walletFacade;
    @Autowired WalletRepository walletRepository;
    @Autowired HoldingRepository holdingRepository;
    @Autowired HoldingSettlementRepository holdingSettlementRepository;
    @Autowired WalletTransactionRepository walletTransactionRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @AfterEach
    void clean() {
        walletTransactionRepository.deleteAllInBatch();
        holdingSettlementRepository.deleteAllInBatch();
        holdingRepository.deleteAllInBatch();
        walletRepository.deleteAllInBatch();
    }

    @Test
    void 지갑이_없는_회원을_조회하면_0원_지갑을_만들고_다시_조회해도_같은_지갑이다() {
        WalletBalanceResponse first = walletFacade.getWallet(7L);
        WalletBalanceResponse second = walletFacade.getWallet(7L);

        assertThat(first.balance()).isZero();
        assertThat(second.walletId()).isEqualTo(first.walletId());
        assertThat(walletRepository.count()).isOne();
    }

    @Test
    void 지갑이_없는_회원을_동시에_처음_조회해도_지갑은_하나이고_모두_성공한다() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Long>> futures = IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return walletFacade.getWallet(8L).walletId();
                    }))
                    .toList();
            start.countDown();

            Long walletId = futures.get(0).get();
            for (Future<Long> future : futures) {
                assertThat(future.get()).isEqualTo(walletId); // 예외 없이 모두 같은 지갑
            }
            assertThat(walletRepository.count()).isOne();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 지갑이_없는_판매자도_정산금을_입금받는다() {
        long sellerMemberId = 20L;
        transactionTemplate.executeWithoutResult(status -> {
            Wallet buyer = walletRepository.save(Wallet.createWallet(10L));
            holdingRepository.save(Holding.create(buyer, 100L, WalletPurchaseType.INDIVIDUAL_GROUP, 10_000L, 1L));
        });

        transactionTemplate.executeWithoutResult(status -> walletFacade.settleAndDeposit(
                new SettleAndDepositRequest(1L, 100L, WalletPurchaseType.INDIVIDUAL_GROUP, sellerMemberId, 10_000L, 10_000L)));

        assertThat(walletRepository.findByMemberId(sellerMemberId)).hasValueSatisfying(
                wallet -> assertThat(wallet.getBalance()).isEqualTo(10_000L));
    }
}
