package com.cartmate.cartmate.boundedContext.productService.payback.out;

import org.springframework.transaction.annotation.Transactional;
import com.cartmate.cartmate.boundedContext.productService.payback.domain.IndividualGroupPurchasePayback;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.util.Optional;

import static com.cartmate.cartmate.boundedContext.productService.payback.domain.QIndividualGroupPurchasePayback.individualGroupPurchasePayback;

// 예전 @Query 메서드처럼 트랜잭션 밖에서 불러도 동작하게 한다(안에서 부르면 그 트랜잭션에 합류)
@Transactional
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
