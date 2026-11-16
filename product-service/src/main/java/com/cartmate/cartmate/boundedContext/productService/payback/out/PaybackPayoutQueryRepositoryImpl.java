package com.cartmate.cartmate.boundedContext.productService.payback.out;

import com.cartmate.cartmate.boundedContext.productService.payback.domain.PaybackPayout;
import com.cartmate.cartmate.boundedContext.productService.payback.domain.PaybackPayoutStatus;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;

import java.util.List;
import java.util.Optional;

import static com.cartmate.cartmate.boundedContext.productService.payback.domain.QPaybackPayout.paybackPayout;

public class PaybackPayoutQueryRepositoryImpl implements PaybackPayoutQueryRepository {
    private final JPAQueryFactory queryFactory;

    public PaybackPayoutQueryRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Optional<PaybackPayout> findByIdForUpdate(Long id) {
        return Optional.ofNullable(queryFactory
                .selectFrom(paybackPayout)
                .where(paybackPayout.id.eq(id))
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .fetchOne());
    }

    @Override
    public List<Long> findIdsByStatus(PaybackPayoutStatus status, Limit limit) {
        JPAQuery<Long> query = queryFactory
                .select(paybackPayout.id)
                .from(paybackPayout)
                .where(paybackPayout.status.eq(status))
                .orderBy(paybackPayout.id.asc());
        if (limit != null && limit.isLimited()) {
            query.limit(limit.max());
        }
        return query.fetch();
    }
}
