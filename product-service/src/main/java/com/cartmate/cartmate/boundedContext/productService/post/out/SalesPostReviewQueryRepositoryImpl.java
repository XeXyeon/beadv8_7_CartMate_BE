package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;

import static com.cartmate.cartmate.boundedContext.productService.post.domain.QSalesPostReview.salesPostReview;

public class SalesPostReviewQueryRepositoryImpl implements SalesPostReviewQueryRepository {
    private final EntityManager entityManager;
    private final JPAQueryFactory queryFactory;

    public SalesPostReviewQueryRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    // @Modifying(clearAutomatically = true)와 같게: 실행 뒤 비우기
    @Override
    public int anonymizeWriter(Long memberId, String nickname) {
        long changed = queryFactory.update(salesPostReview)
                .set(salesPostReview.writerNickname, nickname)
                .where(salesPostReview.writerMemberId.eq(memberId))
                .execute();
        entityManager.clear();
        return (int) changed;
    }
}
