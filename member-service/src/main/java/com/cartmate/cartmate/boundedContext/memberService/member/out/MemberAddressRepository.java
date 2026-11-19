package com.cartmate.cartmate.boundedContext.memberService.member.out;

import com.cartmate.cartmate.boundedContext.memberService.member.domain.MemberAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MemberAddressRepository extends JpaRepository<MemberAddress, Long> {

    Optional<MemberAddress> findFirstByMemberIdAndDefaultAddressTrue(Long memberId);

    // 기본 배송지 먼저, 그다음 최근 등록 순
    List<MemberAddress> findAllByMemberIdOrderByDefaultAddressDescIdDesc(Long memberId);

    long countByMemberId(Long memberId);

    // 탈퇴 확정: 배송지 전부 삭제
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MemberAddress a where a.member.id = :memberId")
    int deleteAllOf(@Param("memberId") Long memberId);
}