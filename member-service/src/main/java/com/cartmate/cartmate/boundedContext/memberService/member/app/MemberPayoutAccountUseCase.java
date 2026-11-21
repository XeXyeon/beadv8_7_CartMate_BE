package com.cartmate.cartmate.boundedContext.memberService.member.app;

import com.cartmate.cartmate.boundedContext.memberService.member.domain.BankCode;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.EmailVerificationProperties;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.Member;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.MemberPayoutAccount;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.MemberRole;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.Seller;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.SellerAccountProperties;
import com.cartmate.cartmate.boundedContext.memberService.member.dto.PayoutAccountInternalResponse;
import com.cartmate.cartmate.boundedContext.memberService.member.dto.PayoutAccountResponse;
import com.cartmate.cartmate.boundedContext.memberService.member.exception.MemberErrorCode;
import com.cartmate.cartmate.boundedContext.memberService.member.out.AccountVerificationStore;
import com.cartmate.cartmate.boundedContext.memberService.member.out.MemberPayoutAccountRepository;
import com.cartmate.cartmate.boundedContext.memberService.member.out.MemberRepository;
import com.cartmate.cartmate.boundedContext.memberService.member.out.SellerRepository;
import com.cartmate.cartmate.boundedContext.memberService.member.out.VerificationCodeStore;
import com.cartmate.cartmate.boundedContext.memberService.member.out.VerificationCodeStore.IssueResult;
import com.cartmate.cartmate.boundedContext.memberService.member.out.VerificationCodeStore.VerifyResult;
import com.cartmate.cartmate.boundedContext.memberService.member.out.VerificationMailSender;
import com.cartmate.cartmate.boundedContext.memberService.member.out.account.AccountVerifier;
import com.cartmate.cartmate.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 예치금 출금 계좌 (회원당 1개, 본인 명의).
 *
 * <ul>
 *   <li>등록·변경 전에 이메일 인증번호를 확인한다 (가입 이메일 인증과 같은 규칙: 5분 유효, 재발송 60초·5번, 5번 틀리면 막힘).
 *       토큰을 빼앗겨도 계좌를 바꾸려면 메일함이 필요하다. 출금은 등록된 본인 계좌로만 가므로 위험은 계좌 변경에 모인다</li>
 *   <li>직접 입력한 계좌는 오픈뱅킹 실명 확인(실명번호 = 생년월일)을 통과해야 한다. 판매자는 이미 확인한 정산 계좌를 그대로 쓸 수 있다</li>
 *   <li>확인 한 번에 변경 한 번: 저장이 커밋되면 확인 기록을 지운다. 바뀌면 알림 메일</li>
 * </ul>
 * 인증번호 발송·실명 확인은 트랜잭션 밖에서 부른다 (SMTP·외부 HTTP 동안 DB 연결을 잡지 않음).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberPayoutAccountUseCase {

    private static final String PURPOSE = "payout-account:";
    private static final DateTimeFormatter BIRTH = DateTimeFormatter.ofPattern("yyMMdd");

    private final MemberRepository memberRepository;
    private final SellerRepository sellerRepository;
    private final MemberPayoutAccountRepository payoutAccountRepository;
    private final PayoutAccountCipher cipher;
    private final VerificationCodeStore codeStore;
    private final VerificationCodeHasher hasher;
    private final VerificationMailSender mailSender;
    private final EmailVerificationProperties emailProperties;
    private final AccountVerifier accountVerifier;
    private final AccountVerificationStore accountVerificationStore;
    private final SellerAccountProperties sellerAccountProperties;
    private final SecurityAlertMailer securityAlertMailer;

    // 인증번호 보내기 (트랜잭션 밖). 돌려주는 값: 코드 유효 시간(초)
    public int sendCode(Long memberId, Instant now) {
        Member member = findAllowedMember(memberId);
        String key = PURPOSE + memberId;
        String code = hasher.newCode();
        IssueResult issued = codeStore.issue(key, hasher.hash(key, code), now, emailProperties.codeTtl(),
                emailProperties.resendCooldown(), emailProperties.maxSends(), emailProperties.maxAttempts(),
                emailProperties.sendWindow());
        switch (issued.status()) {
            case TOO_SOON -> throw new ServiceException(MemberErrorCode.MEMBER_EMAIL_RESEND_TOO_SOON.resultCode(),
                    (issued.retryAfter().toSeconds() + 1) + "초 후에 다시 요청해주세요.");
            case TOO_MANY_SENDS -> throw new ServiceException(MemberErrorCode.MEMBER_EMAIL_TOO_MANY_SENDS);
            case TOO_MANY_ATTEMPTS -> throw new ServiceException(MemberErrorCode.MEMBER_EMAIL_TOO_MANY_ATTEMPTS);
            case ISSUED -> {
            }
        }

        try {
            mailSender.send(member.getEmail(), "[CartMate] 출금 계좌 변경 인증번호",
                    "인증번호는 " + code + " 입니다.\n" + MemberEmailVerificationUseCase.readable(emailProperties.codeTtl())
                            + " 안에 입력해주세요.\n본인이 요청하지 않았다면 비밀번호를 바꿔주세요.");
        } catch (MailException e) {
            codeStore.releaseCooldown(key);
            log.warn("[출금 계좌] 인증 메일 발송 실패: 회원 #{}", memberId, e);
            throw new ServiceException(MemberErrorCode.MEMBER_EMAIL_SEND_FAILED);
        }
        return (int) emailProperties.codeTtl().toSeconds();
    }

    public void verifyCode(Long memberId, String code, Instant now) {
        String key = PURPOSE + memberId;
        VerifyResult result = codeStore.verify(key, hasher.hash(key, code), now,
                emailProperties.maxAttempts(), emailProperties.verifiedTtl());
        switch (result.status()) {
            case VERIFIED -> {
            }
            case NOT_SENT -> throw new ServiceException(MemberErrorCode.MEMBER_EMAIL_CODE_NOT_SENT);
            case TOO_MANY_ATTEMPTS -> throw new ServiceException(MemberErrorCode.MEMBER_EMAIL_TOO_MANY_ATTEMPTS);
            case EXPIRED -> throw new ServiceException(MemberErrorCode.MEMBER_EMAIL_CODE_EXPIRED);
            case MISMATCH -> throw new ServiceException(MemberErrorCode.MEMBER_EMAIL_CODE_MISMATCH.resultCode(),
                    "인증번호가 일치하지 않습니다. (" + Math.max(0, emailProperties.maxAttempts() - result.attempts()) + "번 남음)");
        }
    }

    public void ensureEmailVerified(Long memberId, Instant now) {
        if (!codeStore.isVerified(PURPOSE + memberId, now)) {
            throw new ServiceException(MemberErrorCode.PAYOUT_ACCOUNT_EMAIL_NOT_VERIFIED);
        }
    }

    // 실명 확인 (트랜잭션 밖, 외부 HTTP). 판매자 계좌 확인과 호출 횟수 제한을 같이 쓴다
    public BankCode verifyOwnAccount(Long memberId, String bankName, String accountHolder, String accountNumber) {
        Member member = findAllowedMember(memberId);
        BankCode bank = BankCode.fromName(bankName);
        if (!accountVerificationStore.tryAcquireAttempt(memberId, sellerAccountProperties.maxAttempts(),
                sellerAccountProperties.attemptWindow())) {
            throw new ServiceException(MemberErrorCode.SELLER_ACCOUNT_VERIFY_TOO_MANY_REQUESTS);
        }
        // 실명번호 = 회원 생년월일 → 다른 사람 명의 계좌는 통과하지 못한다
        if (!accountVerifier.isHolderMatched(bank, accountNumber, member.getBirthDate().format(BIRTH),
                List.of(accountHolder))) {
            throw new ServiceException(MemberErrorCode.PAYOUT_ACCOUNT_MISMATCH);
        }
        return bank;
    }

    // 트랜잭션 안: 저장(없으면 만들고 있으면 바꿈) → 커밋되면 확인 기록 삭제 + 알림 메일
    public PayoutAccountResponse save(Long memberId, BankCode bank, String accountHolder, String accountNumber) {
        Member member = findAllowedMember(memberId);
        String encrypted = cipher.encrypt(accountNumber);
        MemberPayoutAccount account = payoutAccountRepository.findByMemberId(memberId)
                .map(existing -> {
                    existing.change(bank, accountHolder, encrypted, accountNumber);
                    return existing;
                })
                .orElseGet(() -> payoutAccountRepository.save(
                        MemberPayoutAccount.register(memberId, bank, accountHolder, encrypted, accountNumber)));
        payoutAccountRepository.flush(); // 수정일을 응답에 싣는다

        removeVerificationAfterCommit(memberId);
        securityAlertMailer.sendAfterCommit(memberId, member.getEmail(), "[CartMate] 출금 계좌가 등록·변경되었습니다",
                "출금 계좌가 " + account.getBankName() + " " + account.getMaskedAccountNumber() + "(으)로 등록·변경되었습니다.\n"
                        + "본인이 하지 않았다면 바로 비밀번호를 바꾸고 고객센터에 알려주세요.");
        return PayoutAccountResponse.from(account);
    }

    // 트랜잭션 안: 판매자 정산 계좌 (판매자 등록 때 실명 확인을 마친 계좌)
    public PayoutAccountResponse saveFromSeller(Long memberId) {
        Seller seller = sellerRepository.findByMemberId(memberId)
                .orElseThrow(() -> new ServiceException(MemberErrorCode.PAYOUT_ACCOUNT_SELLER_NOT_FOUND));
        if (seller.getAccountNumber() == null || seller.getBankName() == null) {
            throw new ServiceException(MemberErrorCode.PAYOUT_ACCOUNT_SELLER_NOT_FOUND);
        }
        return save(memberId, BankCode.fromName(seller.getBankName()), seller.getAccountHolder(), seller.getAccountNumber());
    }

    public Optional<PayoutAccountResponse> get(Long memberId) {
        return payoutAccountRepository.findByMemberId(memberId).map(PayoutAccountResponse::from);
    }

    // 결제 서버 내부 API: 원문 계좌번호
    public Optional<PayoutAccountInternalResponse> getForPayout(Long memberId) {
        return payoutAccountRepository.findByMemberId(memberId)
                .map(account -> new PayoutAccountInternalResponse(account.getBankCode(), account.getBankName(),
                        account.getAccountHolder(), cipher.decrypt(account.getAccountNumberEncrypted())));
    }

    // 관리자(플랫폼 수수료 지갑)는 이 흐름으로 돈을 빼지 않는다
    private Member findAllowedMember(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ServiceException(MemberErrorCode.MEMBER_NOT_FOUND));
        if (member.getRole() == MemberRole.ADMIN) {
            throw new ServiceException(MemberErrorCode.PAYOUT_ACCOUNT_ADMIN_NOT_ALLOWED);
        }
        return member;
    }

    // 커밋 전에 지우면 롤백돼도 확인만 사라진다 → 커밋 뒤에. 지우지 못해도 확인 유효시간이 지나면 사라진다
    private void removeVerificationAfterCommit(Long memberId) {
        Runnable remove = () -> {
            try {
                codeStore.remove(PURPOSE + memberId);
            } catch (RuntimeException e) {
                log.warn("[출금 계좌] 이메일 확인 기록 삭제 실패: 회원 #{}", memberId, e);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            remove.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                remove.run();
            }
        });
    }
}
