package com.cartmate.cartmate.boundedContext.memberService.member.out;

import com.cartmate.cartmate.boundedContext.memberService.member.domain.MemberPayoutAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberPayoutAccountRepository extends JpaRepository<MemberPayoutAccount, Long> {

    Optional<MemberPayoutAccount> findByMemberId(Long memberId);
}
