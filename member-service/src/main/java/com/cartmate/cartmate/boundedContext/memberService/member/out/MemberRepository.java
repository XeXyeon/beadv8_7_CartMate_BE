package com.cartmate.cartmate.boundedContext.memberService.member.out;

import com.cartmate.cartmate.boundedContext.memberService.member.domain.Member;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.MemberStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long>,
        JpaSpecificationExecutor<Member>, MemberRepositoryCustom {

    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);

    Optional<Member> findByEmail(String email);

    boolean existsByNicknameAndIdNot(String nickname, Long id);

    @Query("select m.id from Member m where m.status = :status")
    List<Long> findIdsByStatus(@Param("status") MemberStatus status);

    List<Member> findTop5ByOrderByIdDesc();

    // ===== 회원 탈퇴 =====

    // 탈퇴 확정 대상: 유예 기간이 끝난 탈퇴 대기 회원. id 커서로 나눠 읽는다 (막혀서 건너뛴 회원을 다시 읽지 않게)
    @Query("select m.id from Member m where m.status = :pending and m.withdrawalRequestedAt <= :requestedBefore " +
           "and m.id > :afterId order by m.id")
    List<Long> findIdsDueForWithdrawal(@Param("pending") MemberStatus pending,
                                       @Param("requestedBefore") LocalDateTime requestedBefore,
                                       @Param("afterId") Long afterId, Limit limit);

    // 탈퇴 취소: 아직 대기 중일 때만 (확정과 동시에 와도 둘 중 하나만 성공)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Member m set m.status = :active, m.withdrawalRequestedAt = null " +
           "where m.id = :id and m.status = :pending")
    int cancelWithdrawal(@Param("id") Long id, @Param("pending") MemberStatus pending,
                         @Param("active") MemberStatus active);

    // 탈퇴 확정 + 개인정보 덮어쓰기를 UPDATE 하나로: 대기 중이고 유예 기간이 끝났을 때만 (취소와 동시에 와도 하나만 성공)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Member m set m.status = :withdrawn, m.deletedAt = :now, " +
           "m.email = :email, m.password = :password, m.nickname = :nickname, m.phone = :phone, m.birthDate = :birthDate " +
           "where m.id = :id and m.status = :pending and m.withdrawalRequestedAt <= :requestedBefore")
    int finalizeWithdrawal(@Param("id") Long id, @Param("pending") MemberStatus pending,
                           @Param("withdrawn") MemberStatus withdrawn,
                           @Param("requestedBefore") LocalDateTime requestedBefore, @Param("now") LocalDateTime now,
                           @Param("email") String email, @Param("password") String password,
                           @Param("nickname") String nickname, @Param("phone") String phone,
                           @Param("birthDate") LocalDate birthDate);

    // 탈퇴 확정 시각이 [from, to)인 회원 (다른 서비스가 정리할 대상을 받아 간다)
    @Query("select m.id from Member m where m.status = :withdrawn and m.deletedAt >= :from and m.deletedAt < :to order by m.id")
    List<Long> findWithdrawnIdsBetween(@Param("withdrawn") MemberStatus withdrawn,
                                       @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select m.id from Member m where m.status = :withdrawn and m.deletedAt < :to order by m.id")
    List<Long> findWithdrawnIdsBefore(@Param("withdrawn") MemberStatus withdrawn, @Param("to") LocalDateTime to);
}
