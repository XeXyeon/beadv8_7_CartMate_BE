package com.cartmate.cartmate.boundedContext.memberService.member.out;

import com.cartmate.cartmate.boundedContext.memberService.member.domain.Seller;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SellerRepository extends JpaRepository<Seller, Long> {

    Optional<Seller> findByMemberId(Long memberId);

    boolean existsByMemberId(Long memberId);

    boolean existsByBusinessNumber(String businessNumber);

    boolean existsByBankNameAndAccountNumber(String bankName, String accountNumber);

    // 판매자 등록 이벤트 다시 발행(백필)용 id 커서 페이지. offset 대신 id로 넘겨 뒤로 가도 느려지지 않는다.
    List<Seller> findByIdGreaterThanOrderByIdAsc(Long id, Limit limit);

    // 탈퇴 확정: 행(seller_id)은 남기고 사업자·연락처·정산 계좌를 지운다. 사업자번호·계좌 unique도 풀려 다시 등록할 수 있다
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Seller s set s.businessName = null, s.representativeName = null, s.businessNumber = null, " +
           "s.phoneNumber = '', s.bankName = null, s.accountHolder = null, s.accountNumber = null " +
           "where s.member.id = :memberId")
    int anonymizeByMemberId(@Param("memberId") Long memberId);
}