package com.cartmate.cartmate.boundedContext.memberService.member.domain;

import com.cartmate.cartmate.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 예치금 출금 계좌 (회원당 1개). 본인 명의만 받는다 (오픈뱅킹 실명 확인 통과).
 *
 * <p>계좌번호는 AES-GCM으로 잠가 둔다 ({@code account_number_enc}). 화면에는 끝 4자리만 보인다.
 * 결제 서버가 출금을 신청받을 때 내부 API로 풀어서 받아 가 출금 건에 따로 보관한다 (신청 뒤 계좌를 바꿔도 그 건은 신청 때 계좌로).
 */
@Entity
@Table(name = "member_payout_account",
        uniqueConstraints = @UniqueConstraint(name = "uk_member_payout_account_member", columnNames = "member_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberPayoutAccount extends BaseIdAndTime {

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "bank_name", nullable = false, length = 20)
    private String bankName;

    @Column(name = "bank_code", nullable = false, length = 3)
    private String bankCode;

    @Column(name = "account_holder", nullable = false, length = 50)
    private String accountHolder;

    @Column(name = "account_number_enc", nullable = false, length = 255)
    private String accountNumberEncrypted;

    @Column(name = "account_number_last4", nullable = false, length = 4)
    private String accountNumberLast4;

    public static MemberPayoutAccount register(Long memberId, BankCode bank, String accountHolder,
                                              String accountNumberEncrypted, String accountNumber) {
        MemberPayoutAccount account = new MemberPayoutAccount();
        account.memberId = memberId;
        account.change(bank, accountHolder, accountNumberEncrypted, accountNumber);
        return account;
    }

    public void change(BankCode bank, String accountHolder, String accountNumberEncrypted, String accountNumber) {
        this.bankName = bank.getDisplayName();
        this.bankCode = bank.getCode();
        this.accountHolder = accountHolder;
        this.accountNumberEncrypted = accountNumberEncrypted;
        this.accountNumberLast4 = accountNumber.substring(accountNumber.length() - 4);
    }

    public String getMaskedAccountNumber() {
        return "****" + accountNumberLast4;
    }
}
