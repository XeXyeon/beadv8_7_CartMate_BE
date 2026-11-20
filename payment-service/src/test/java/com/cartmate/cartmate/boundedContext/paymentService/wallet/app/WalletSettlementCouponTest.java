package com.cartmate.cartmate.boundedContext.paymentService.wallet.app;

import com.cartmate.cartmate.boundedContext.paymentService.payout.domain.Payout;
import com.cartmate.cartmate.boundedContext.paymentService.payout.domain.PayoutPurchaseType;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.Holding;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.HoldingSettlement;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.HoldingStatus;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.Wallet;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WalletTransaction;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.domain.WalletTransactionType;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.HoldingRepository;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.HoldingSettlementRepository;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.WalletRepository;
import com.cartmate.cartmate.boundedContext.paymentService.wallet.out.WalletTransactionRepository;
import com.cartmate.cartmate.shared.wallet.WalletPurchaseType;
import com.cartmate.cartmate.shared.wallet.dto.SettleAndDepositRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 쿠폰을 쓴 주문의 정산: 홀딩에는 구매자가 실제로 낸 돈만 있으므로 할인 전 금액으로 정산하면 홀딩이 모자라 지급이 실패했다.
// 정산(Payout)이 판매자 부담 할인을 빼고, 플랫폼 부담 할인은 플랫폼 지갑이 내게 고쳤다.
class WalletSettlementCouponTest {

    private static final Long ORDER_ID = 100L;
    private static final Long SELLER_MEMBER_ID = 7L;
    private static final LocalDateTime CONFIRMED_AT = LocalDateTime.of(2026, 11, 20, 10, 0);

    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final HoldingRepository holdingRepository = mock(HoldingRepository.class);
    private final HoldingSettlementRepository holdingSettlementRepository = mock(HoldingSettlementRepository.class);
    private final PlatformWalletProvider platformWalletProvider = mock(PlatformWalletProvider.class);
    private final WalletTransactionRepository walletTransactionRepository = mock(WalletTransactionRepository.class);

    private final WalletSettlementUseCase useCase = new WalletSettlementUseCase(
            walletRepository, holdingRepository, holdingSettlementRepository, platformWalletProvider,
            new LedgerRecorder(walletTransactionRepository), walletTransactionRepository);

    private final Map<Long, Long> settledByHolding = new HashMap<>();
    private final List<HoldingSettlement> settlements = new ArrayList<>();
    private final List<WalletTransaction> transactions = new ArrayList<>();

    private Wallet sellerWallet;
    private Wallet platformWallet;

    @BeforeEach
    void setUp() {
        sellerWallet = Wallet.createWallet(SELLER_MEMBER_ID);
        platformWallet = Wallet.createWallet(0L);
        when(walletRepository.findByMemberId(SELLER_MEMBER_ID)).thenReturn(Optional.of(sellerWallet));
        when(platformWalletProvider.getPlatformWallet()).thenReturn(platformWallet);

        when(holdingSettlementRepository.save(any(HoldingSettlement.class))).thenAnswer(invocation -> {
            HoldingSettlement settlement = invocation.getArgument(0);
            settlements.add(settlement);
            settledByHolding.merge(settlement.getHolding().getId(), settlement.getAmount(), Long::sum);
            return settlement;
        });
        when(holdingSettlementRepository.sumAmountByHoldingId(anyLong()))
                .thenAnswer(invocation -> settledByHolding.getOrDefault(invocation.<Long>getArgument(0), 0L));
        when(holdingSettlementRepository.findAllByPayoutId(anyLong())).thenAnswer(invocation -> settlements.stream()
                .filter(settlement -> settlement.getPayoutId().equals(invocation.getArgument(0)))
                .toList());
        when(walletTransactionRepository.save(any(WalletTransaction.class))).thenAnswer(invocation -> {
            transactions.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
    }

    @Test
    @DisplayName("판매자 쿠폰 주문: 상품 2개(1만원씩)에 2천원 할인 → 홀딩 1만8천원에서 두 정산이 모두 지급되고 홀딩이 닫힌다")
    void sellerCoupon_bothPayoutsSettle() {
        Holding holding = holding(1L, 18_000L);
        Payout first = payout(1L, 11L, 10_000L, 1_000L, 0L);
        Payout second = payout(2L, 12L, 10_000L, 1_000L, 0L);

        settle(first);
        settle(second);

        // 판매 금액 9,000원, 수수료 270원(3%), 정산금 8,730원 x 2
        assertThat(first.getSalesAmount()).isEqualTo(9_000L);
        assertThat(first.getFeeAmount()).isEqualTo(270L);
        assertThat(sellerWallet.getBalance()).isEqualTo(17_460L);
        assertThat(platformWallet.getBalance()).isEqualTo(540L);
        assertThat(holding.getStatus()).isEqualTo(HoldingStatus.SETTLED);
    }

    @Test
    @DisplayName("플랫폼 쿠폰 주문: 1만원 상품에 3천원 할인 → 홀딩 7천원을 꺼내고 플랫폼이 3천원을 대신 내서 판매자는 정가 기준으로 받는다")
    void platformCoupon_platformPaysDiscount() {
        Holding holding = holding(1L, 7_000L);
        Payout payout = payout(1L, 11L, 10_000L, 0L, 3_000L);

        settle(payout);

        assertThat(payout.getSalesAmount()).isEqualTo(10_000L);
        assertThat(sellerWallet.getBalance()).isEqualTo(9_700L);
        // 플랫폼: 할인 지원 -3,000 + 수수료 300 → 잔액은 음수가 될 수 있다
        assertThat(platformWallet.getBalance()).isEqualTo(-2_700L);
        assertThat(holding.getStatus()).isEqualTo(HoldingStatus.SETTLED);
        assertThat(transactions).extracting(WalletTransaction::getType, WalletTransaction::getAmount)
                .containsExactly(
                        tuple(WalletTransactionType.SETTLEMENT, 9_700L),
                        tuple(WalletTransactionType.COUPON_SUBSIDY, 3_000L),
                        tuple(WalletTransactionType.FEE, 300L));
    }

    @Test
    @DisplayName("플랫폼 쿠폰이 상품 금액 전부를 할인하면 홀딩을 건드리지 않고 플랫폼이 전부 내며, 같은 정산이 다시 와도 한 번만 처리된다")
    void platformCoupon_fullDiscount_skipsHoldingAndIsIdempotent() {
        when(holdingRepository.findAllByOrderIdAndPurchaseType(any(), any())).thenReturn(List.of());
        Payout payout = payout(1L, 11L, 5_000L, 0L, 5_000L);

        settle(payout);
        when(walletTransactionRepository.findByTypeAndOrderIdAndReferenceIdAndReferenceType(
                any(), any(), any(), any())).thenReturn(Optional.of(transactions.get(0)));
        settle(payout);

        assertThat(sellerWallet.getBalance()).isEqualTo(4_850L);
        assertThat(platformWallet.getBalance()).isEqualTo(-4_850L);
        assertThat(settlements).isEmpty();
    }

    private Holding holding(Long id, long amount) {
        Wallet buyerWallet = Wallet.createWallet(99L);
        Holding holding = Holding.create(buyerWallet, ORDER_ID, WalletPurchaseType.INDIVIDUAL_GROUP, amount, 1L);
        ReflectionTestUtils.setField(holding, "id", id);
        when(holdingRepository.findAllByOrderIdAndPurchaseType(ORDER_ID, WalletPurchaseType.INDIVIDUAL_GROUP))
                .thenReturn(List.of(holding));
        return holding;
    }

    private Payout payout(Long id, Long orderItemId, long grossAmount, long sellerDiscount, long platformDiscount) {
        Payout payout = Payout.create(1L, ORDER_ID, PayoutPurchaseType.INDIVIDUAL_GROUP, orderItemId, "상품", 1L,
                grossAmount, sellerDiscount, platformDiscount, CONFIRMED_AT, CONFIRMED_AT);
        ReflectionTestUtils.setField(payout, "id", id);
        return payout;
    }

    private void settle(Payout payout) {
        useCase.settleAndDeposit(new SettleAndDepositRequest(
                payout.getId(), ORDER_ID, WalletPurchaseType.INDIVIDUAL_GROUP, SELLER_MEMBER_ID,
                payout.getSalesAmount(), payout.getPayoutAmount(), payout.platformDiscountOrZero()));
    }
}
