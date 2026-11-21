package com.cartmate.cartmate.boundedContext.memberService.member.app;

import com.cartmate.cartmate.boundedContext.memberService.member.domain.EmailVerificationProperties;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.Member;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.MemberPayoutAccount;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.SellerAccountProperties;
import com.cartmate.cartmate.boundedContext.memberService.member.dto.PayoutAccountRequest;
import com.cartmate.cartmate.boundedContext.memberService.member.out.AccountVerificationStore;
import com.cartmate.cartmate.boundedContext.memberService.member.out.MemberPayoutAccountRepository;
import com.cartmate.cartmate.boundedContext.memberService.member.out.MemberRepository;
import com.cartmate.cartmate.boundedContext.memberService.member.out.SellerRepository;
import com.cartmate.cartmate.boundedContext.memberService.member.out.VerificationCodeStore;
import com.cartmate.cartmate.boundedContext.memberService.member.out.VerificationMailSender;
import com.cartmate.cartmate.boundedContext.memberService.member.out.account.AccountVerifier;
import com.cartmate.cartmate.global.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// 출금 계좌 보안 규칙: 이메일 인증 없이는 바꿀 수 없고, 본인 명의(실명 확인)가 아니면 저장하지 않는다. 계좌번호는 암호화해 둔다
class MemberPayoutAccountTest {

    private static final long MEMBER = 7L;
    private static final Instant NOW = Instant.parse("2026-11-20T01:00:00Z");

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final MemberPayoutAccountRepository accountRepository = mock(MemberPayoutAccountRepository.class);
    private final VerificationCodeStore codeStore = mock(VerificationCodeStore.class);
    private final AccountVerifier accountVerifier = mock(AccountVerifier.class);
    private final AccountVerificationStore attemptStore = mock(AccountVerificationStore.class);
    private final PayoutAccountCipher cipher = new PayoutAccountCipher("Y2FydG1hdGUtbG9jYWwtcGF5b3V0LWFjY3Qta2V5ISE=");

    private final MemberPayoutAccountUseCase useCase = new MemberPayoutAccountUseCase(memberRepository,
            mock(SellerRepository.class), accountRepository, cipher, codeStore,
            new VerificationCodeHasher("test-code-secret-test-code-secret-32"), mock(VerificationMailSender.class),
            new EmailVerificationProperties(null, null, null, null, 0, 0), accountVerifier, attemptStore,
            new SellerAccountProperties(null, 0, null), mock(SecurityAlertMailer.class));

    private final MemberPayoutAccountFacade facade = new MemberPayoutAccountFacade(useCase, immediateTx());

    private static final PayoutAccountRequest REQUEST = new PayoutAccountRequest("국민은행", "회원", "1234567890");

    private void member() {
        Member member = Member.create("m@cartmate.com", "encoded", "회원", "010-1234-5678", LocalDate.of(1990, 1, 1));
        ReflectionTestUtils.setField(member, "id", MEMBER);
        given(memberRepository.findById(MEMBER)).willReturn(Optional.of(member));
        given(attemptStore.tryAcquireAttempt(eq(MEMBER), anyInt(), any())).willReturn(true);
    }

    @Test
    void 이메일_인증_전에는_실명_확인도_하지_않고_막는다() {
        member();
        given(codeStore.isVerified("payout-account:" + MEMBER, NOW)).willReturn(false);

        assertThatThrownBy(() -> facade.register(MEMBER, REQUEST, NOW))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("이메일 인증이 필요합니다");
        verifyNoInteractions(accountVerifier);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void 본인_명의가_아니면_저장하지_않는다() {
        member();
        given(codeStore.isVerified("payout-account:" + MEMBER, NOW)).willReturn(true);
        given(accountVerifier.isHolderMatched(any(), anyString(), eq("900101"), eq(List.of("회원")))).willReturn(false);

        assertThatThrownBy(() -> facade.register(MEMBER, REQUEST, NOW))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("본인 명의 계좌만");
        verify(accountRepository, never()).save(any());
    }

    @Test
    void 확인을_통과하면_계좌번호를_암호화해_저장하고_화면에는_끝_4자리만_준다() {
        member();
        given(codeStore.isVerified("payout-account:" + MEMBER, NOW)).willReturn(true);
        given(accountVerifier.isHolderMatched(any(), eq("1234567890"), eq("900101"), any())).willReturn(true);
        given(accountRepository.findByMemberId(MEMBER)).willReturn(Optional.empty());
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        var response = facade.register(MEMBER, REQUEST, NOW);

        assertThat(response.maskedAccountNumber()).isEqualTo("****7890");
        org.mockito.ArgumentCaptor<MemberPayoutAccount> saved = org.mockito.ArgumentCaptor.forClass(MemberPayoutAccount.class);
        verify(accountRepository).save(saved.capture());
        assertThat(saved.getValue().getAccountNumberEncrypted()).doesNotContain("1234567890");
        assertThat(cipher.decrypt(saved.getValue().getAccountNumberEncrypted())).isEqualTo("1234567890");
    }

    private static TransactionTemplate immediateTx() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        given(tx.execute(any())).willAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        return tx;
    }
}
