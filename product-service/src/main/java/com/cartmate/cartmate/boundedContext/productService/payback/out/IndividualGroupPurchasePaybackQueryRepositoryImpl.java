package com.cartmate.cartmate.boundedContext.productService.payback.out;

import com.cartmate.cartmate.boundedContext.productService.payback.domain.IndividualGroupPurchasePayback;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.util.Optional;

import static com.cartmate.cartmate.boundedContext.productService.payback.domain.QIndividualGroupPurchasePayback.individualGroupPurchasePayback;

public class IndividualGroupPurchasePaybackQueryRepositoryImpl implements IndividualGroupPurchasePaybackQueryRepository {
    private final JPAQueryFactory queryFactory;

    public IndividualGroupPurchasePaybackQueryRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Optional<IndividualGroupPurchasePayback> findByIdForUpdate(Long individualGroupPurchaseId) {
        return Optional.ofNullable(queryFactory
                .selectFrom(individualGroupPurchasePayback)
                .where(individualGroupPurchasePayback.individualGroupPurchaseId.eq(individualGroupPurchaseId))
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .fetchOne());
    }
}
