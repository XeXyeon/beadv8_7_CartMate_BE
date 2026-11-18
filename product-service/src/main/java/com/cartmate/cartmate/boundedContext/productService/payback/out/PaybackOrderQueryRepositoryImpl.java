package com.cartmate.cartmate.boundedContext.productService.payback.out;

import org.springframework.transaction.annotation.Transactional;
import com.cartmate.cartmate.boundedContext.productService.payback.domain.PaybackOrder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.util.Optional;

import static com.cartmate.cartmate.boundedContext.productService.payback.domain.QPaybackOrder.paybackOrder;

// 예전 @Query 메서드처럼 트랜잭션 밖에서 불러도 동작하게 한다(안에서 부르면 그 트랜잭션에 합류)
@Transactional
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
