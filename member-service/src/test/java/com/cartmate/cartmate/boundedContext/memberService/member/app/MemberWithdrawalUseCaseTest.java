package com.cartmate.cartmate.boundedContext.memberService.member.app;

import com.cartmate.cartmate.boundedContext.memberService.member.domain.Member;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.MemberRole;
import com.cartmate.cartmate.boundedContext.memberService.member.dto.WithdrawalCheckResponse;
import com.cartmate.cartmate.boundedContext.memberService.member.dto.WithdrawalCheckResponse.WithdrawalBlocker;
import com.cartmate.cartmate.boundedContext.memberService.member.out.MemberRepository;
import com.cartmate.cartmate.boundedContext.memberService.member.out.SellerRepository;
import com.cartmate.cartmate.boundedContext.memberService.member.out.WithdrawalPendingStore;
import com.cartmate.cartmate.boundedContext.memberService.member.out.WithdrawalTradeClient;
import com.cartmate.cartmate.boundedContext.memberService.member.out.WithdrawalTradeClient.BuyerTrades;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.global.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// 탈퇴 신청·조회 규칙(관리자, 예치금, 예정일 지난 대기)과 확정 직전 재검사
class MemberWithdrawalUseCaseTest {

    private static final long MEMBER = 7L;
    private static final LocalDateTime REQUESTED = LocalDateTime.of(2026, 10, 1, 12, 0);

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final WithdrawalTradeClient tradeClient = mock(WithdrawalTradeClient.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    private final MemberWithdrawalUseCase useCase = new MemberWithdrawalUseCase(memberRepository, mock(SellerRepository.class),
            tradeClient, passwordEncoder, mock(DomainEventPublisher.class), mock(WithdrawalPendingStore.class));

    private Member member() {
        Member member = Member.create("m@cartmate.com", "encoded", "회원", "010-1234-5678", LocalDate.of(1990, 1, 1));
        ReflectionTestUtils.setField(member, "id", MEMBER);
        given(memberRepository.findById(MEMBER)).willReturn(Optional.of(member));
        return member;
    }

    private static BuyerTrades buyer(long walletBalance) {
        return new BuyerTrades(0, 0, 0, 0, 0, walletBalance, 0);
    }

    @Test
    void 관리자는_탈퇴를_신청할_수_없고_다른_서비스에_묻지도_않는다() {
        ReflectionTestUtils.setField(member(), "role", MemberRole.ADMIN);

        assertThatThrownBy(() -> useCase.ensureCanRequest(MEMBER, "pw"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("관리자 계정은 탈퇴할 수 없습니다");
        verifyNoInteractions(tradeClient);
    }

    @Test
    void 예치금이_남아_있으면_탈퇴를_신청할_수_없다() {
        member();
        given(passwordEncoder.matches("pw", "encoded")).willReturn(true);
        given(tradeClient.getBuyerTrades(MEMBER)).willReturn(buyer(12_000));

        assertThatThrownBy(() -> useCase.ensureCanRequest(MEMBER, "pw"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("예치금 12,000원");
    }

    // 출금은 신청 때 잔액에서 빠지지만 실패·취소되면 돌아온다 → 잔액이 0이어도 끝날 때까지 막는다
    @Test
    void 진행_중인_예치금_출금이_있으면_잔액이_0이어도_탈퇴를_신청할_수_없다() {
        member();
        given(passwordEncoder.matches("pw", "encoded")).willReturn(true);
        given(tradeClient.getBuyerTrades(MEMBER)).willReturn(new BuyerTrades(0, 0, 0, 0, 0, 0, 7_000));

        assertThatThrownBy(() -> useCase.ensureCanRequest(MEMBER, "pw"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("출금 7,000원이 처리 중");
    }

    @Test
    void 대기_중_조회는_확정_예정일_전엔_다른_서비스에_묻지_않고_지나면_막는_이유를_보여_준다() {
        member().requestWithdrawal(REQUESTED);
        LocalDateTime due = REQUESTED.plusDays(MemberWithdrawalUseCase.GRACE_DAYS);

        WithdrawalCheckResponse before = useCase.check(MEMBER, due.minusSeconds(1));
        assertThat(before.blockers()).isEmpty();
        verifyNoInteractions(tradeClient);

        given(tradeClient.getBuyerTrades(MEMBER)).willReturn(buyer(500));
        WithdrawalCheckResponse after = useCase.check(MEMBER, due);
        assertThat(after.blockers()).extracting(WithdrawalBlocker::code).containsExactly("WALLET_BALANCE_REMAINING");
        assertThat(after.scheduledAt()).isEqualTo(due);
    }

    // 확정 직전 재검사에서 막히면(유예 중 생긴 거래·환불) 확정 트랜잭션을 시작하지 않는다
    @Test
    void 재검사에서_막힌_회원은_확정하지_않고_다음_회원은_확정한다() {
        MemberWithdrawalUseCase withdrawal = mock(MemberWithdrawalUseCase.class);
        MemberWithdrawalFinalizeUseCase finalize = mock(MemberWithdrawalFinalizeUseCase.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        given(tx.execute(any())).willAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        MemberFacade facade = facadeWith(withdrawal, finalize, tx);

        LocalDateTime now = LocalDateTime.of(2026, 11, 19, 4, 0);
        given(finalize.findDueIds(now, 0L)).willReturn(List.of(1L, 2L));
        given(withdrawal.blockersOf(1L)).willReturn(List.of(new WithdrawalBlocker("SELLER_UNCONFIRMED_SALES", "m", 1)));
        given(withdrawal.blockersOf(2L)).willReturn(List.of());
        given(finalize.finalizeOne(2L, now)).willReturn(true);

        MemberFacade.WithdrawalFinalizeResult result = facade.finalizeDueWithdrawals(now);

        verify(finalize, never()).finalizeOne(1L, now);
        assertThat(result.finalized()).isOne();
        assertThat(result.blocked()).isOne();
    }

    private static MemberFacade facadeWith(MemberWithdrawalUseCase withdrawal, MemberWithdrawalFinalizeUseCase finalize,
                                           TransactionTemplate tx) {
        MemberFacade facade = mock(MemberFacade.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(facade, "memberWithdrawalUseCase", withdrawal);
        ReflectionTestUtils.setField(facade, "memberWithdrawalFinalizeUseCase", finalize);
        ReflectionTestUtils.setField(facade, "transactionTemplate", tx);
        return facade;
    }
}
