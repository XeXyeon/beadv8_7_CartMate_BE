package com.cartmate.cartmate.boundedContext.memberService.member.out;

import com.cartmate.cartmate.boundedContext.memberService.member.domain.Seller;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SellerRepository extends JpaRepository<Seller, Long> {

    Optional<Seller> findByMemberId(Long memberId);

    boolean existsByMemberId(Long memberId);

    boolean existsByBusinessNumber(String businessNumber);

    boolean existsByBankNameAndAccountNumber(String bankName, String accountNumber);

    // 판매자 등록 이벤트 다시 발행(백필)용 id 커서 페이지. offset 대신 id로 넘겨 뒤로 가도 느려지지 않는다.
    List<Seller> findByIdGreaterThanOrderByIdAsc(Long id, Limit limit);
}