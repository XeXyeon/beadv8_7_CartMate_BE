package com.cartmate.cartmate.boundedContext.productService.coupon.out;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;

import static com.cartmate.cartmate.boundedContext.productService.coupon.domain.QCoupon.coupon;

public class CouponQueryRepositoryImpl implements CouponQueryRepository {
    private final EntityManager entityManager;
    private final JPAQueryFactory queryFactory;

    public CouponQueryRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    // @Modifying(flushAutomatically = true, clearAutomatically = true)와 같게: 실행 전 flush, 실행 뒤 비우기
    @Override
    public int increaseIssuedCount(Long id) {
        entityManager.flush();
        long changed = queryFactory.update(coupon)
                .set(coupon.issuedCount, coupon.issuedCount.add(1))
                .where(coupon.id.eq(id),
                        coupon.totalQuantity.isNull().or(coupon.issuedCount.lt(coupon.totalQuantity)))
                .execute();
        entityManager.clear();
        return (int) changed;
    }
}
