package com.cartmate.cartmate.boundedContext.memberService.member.app;

import com.cartmate.cartmate.boundedContext.memberService.member.domain.BankCode;
import com.cartmate.cartmate.boundedContext.memberService.member.dto.PayoutAccountInternalResponse;
import com.cartmate.cartmate.boundedContext.memberService.member.dto.PayoutAccountRequest;
import com.cartmate.cartmate.boundedContext.memberService.member.dto.PayoutAccountResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;

// 예치금 출금 계좌. 인증번호 발송·실명 확인(외부 HTTP)은 트랜잭션 밖, 저장만 트랜잭션 안
@Service
@RequiredArgsConstructor
public class MemberPayoutAccountFacade {

    private final MemberPayoutAccountUseCase useCase;
    private final TransactionTemplate transactionTemplate;

    public int sendCode(Long memberId, Instant now) {
        return useCase.sendCode(memberId, now);
    }

    public void verifyCode(Long memberId, String code, Instant now) {
        useCase.verifyCode(memberId, code, now);
    }

    public PayoutAccountResponse register(Long memberId, PayoutAccountRequest request, Instant now) {
        useCase.ensureEmailVerified(memberId, now); // 실명 확인(외부 호출 횟수 제한) 전에 거른다
        String accountHolder = request.accountHolder().trim();
        String accountNumber = request.accountNumber().trim();
        BankCode bank = useCase.verifyOwnAccount(memberId, request.bankName().trim(), accountHolder, accountNumber);
        return transactionTemplate.execute(status -> useCase.save(memberId, bank, accountHolder, accountNumber));
    }

    @Transactional
    public PayoutAccountResponse registerFromSeller(Long memberId, Instant now) {
        useCase.ensureEmailVerified(memberId, now);
        return useCase.saveFromSeller(memberId);
    }

    @Transactional(readOnly = true)
    public Optional<PayoutAccountResponse> get(Long memberId) {
        return useCase.get(memberId);
    }

    @Transactional(readOnly = true)
    public Optional<PayoutAccountInternalResponse> getForPayout(Long memberId) {
        return useCase.getForPayout(memberId);
    }
}
