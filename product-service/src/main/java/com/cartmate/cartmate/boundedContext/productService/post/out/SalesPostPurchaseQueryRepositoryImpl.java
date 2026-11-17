package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostPurchase;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;

import java.time.LocalDateTime;
import java.util.List;

import static com.cartmate.cartmate.boundedContext.productService.post.domain.QSalesPostPurchase.salesPostPurchase;
import static com.cartmate.cartmate.boundedContext.productService.post.domain.QSalesPostReview.salesPostReview;

public class SalesPostPurchaseQueryRepositoryImpl implements SalesPostPurchaseQueryRepository {
    private final JPAQueryFactory queryFactory;

    public SalesPostPurchaseQueryRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public List<SalesPostPurchase> findWritable(Long memberId, LocalDateTime since) {
        return queryFactory
                .selectFrom(salesPostPurchase)
                .where(salesPostPurchase.buyerMemberId.eq(memberId),
                        salesPostPurchase.confirmedAt.isNotNull(),
                        salesPostPurchase.confirmedAt.goe(since),
                        JPAExpressions.selectOne()
                                .from(salesPostReview)
                                .where(salesPostReview.purchaseId.eq(salesPostPurchase.id))
                                .notExists())
                .orderBy(salesPostPurchase.confirmedAt.asc(), salesPostPurchase.id.asc())
                .fetch();
    }
}
