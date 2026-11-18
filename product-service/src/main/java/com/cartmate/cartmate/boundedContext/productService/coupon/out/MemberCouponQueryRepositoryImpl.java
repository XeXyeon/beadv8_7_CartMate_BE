package com.cartmate.cartmate.boundedContext.productService.coupon.out;

import org.springframework.transaction.annotation.Transactional;
import com.cartmate.cartmate.boundedContext.productService.coupon.domain.MemberCoupon;
import com.cartmate.cartmate.boundedContext.productService.coupon.domain.MemberCouponStatus;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.cartmate.cartmate.boundedContext.productService.coupon.domain.QMemberCoupon.memberCoupon;

// 예전 @Query 메서드처럼 트랜잭션 밖에서 불러도 동작하게 한다(안에서 부르면 그 트랜잭션에 합류)
@Transactional
public class MemberCouponQueryRepositoryImpl implements MemberCouponQueryRepository {
    private final JPAQueryFactory queryFactory;

    public MemberCouponQueryRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public List<Long> findOwnedCouponIds(Long memberId, Collection<Long> couponIds) {
        return queryFactory
                .select(memberCoupon.couponId)
                .from(memberCoupon)
                .where(memberCoupon.memberId.eq(memberId), memberCoupon.couponId.in(couponIds))
                .fetch();
    }

    @Override
    public List<Object[]> countByCouponIdsAndStatus(Collection<Long> couponIds, MemberCouponStatus status) {
        return queryFactory
                .select(memberCoupon.couponId, memberCoupon.count())
                .from(memberCoupon)
                .where(memberCoupon.couponId.in(couponIds), memberCoupon.status.eq(status))
                .groupBy(memberCoupon.couponId)
                .fetch().stream()
                .map(row -> new Object[]{row.get(memberCoupon.couponId), row.get(memberCoupon.count())})
                .toList();
    }

    @Override
    public Optional<MemberCoupon> findByIdForUpdate(Long id) {
        return Optional.ofNullable(queryFactory
                .selectFrom(memberCoupon)
                .where(memberCoupon.id.eq(id))
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .fetchOne());
    }
}
