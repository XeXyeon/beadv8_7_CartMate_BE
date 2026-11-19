package com.cartmate.cartmate.boundedContext.productService.post.out;

import org.springframework.transaction.annotation.Transactional;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostPurchase;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;

import java.time.LocalDateTime;
import java.util.List;

import static com.cartmate.cartmate.boundedContext.productService.post.domain.QSalesPostPurchase.salesPostPurchase;
import static com.cartmate.cartmate.boundedContext.productService.post.domain.QSalesPostReview.salesPostReview;

// 예전 @Query 메서드처럼 트랜잭션 밖에서 불러도 동작하게 한다(안에서 부르면 그 트랜잭션에 합류)
@Transactional
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
