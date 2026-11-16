package com.cartmate.cartmate.boundedContext.productService.payback.out;

import com.cartmate.cartmate.boundedContext.productService.payback.domain.PaybackOrder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.util.Optional;

import static com.cartmate.cartmate.boundedContext.productService.payback.domain.QPaybackOrder.paybackOrder;

public class PaybackOrderQueryRepositoryImpl implements PaybackOrderQueryRepository {
    private final JPAQueryFactory queryFactory;

    public PaybackOrderQueryRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Optional<PaybackOrder> findByIdForUpdate(Long orderId) {
        return Optional.ofNullable(queryFactory
                .selectFrom(paybackOrder)
                .where(paybackOrder.orderId.eq(orderId))
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .fetchOne());
    }

    @Override
    public Optional<Long> findIndividualGroupPurchaseId(Long orderId) {
        return Optional.ofNullable(queryFactory
                .select(paybackOrder.individualGroupPurchaseId)
                .from(paybackOrder)
                .where(paybackOrder.orderId.eq(orderId))
                .fetchOne());
    }
}
