package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;

import java.util.Collection;
import java.util.List;

import static com.cartmate.cartmate.boundedContext.productService.post.domain.QSalesPostReview.salesPostReview;

public class SalesPostReviewQueryRepositoryImpl implements SalesPostReviewQueryRepository {
    private final EntityManager entityManager;
    private final JPAQueryFactory queryFactory;

    public SalesPostReviewQueryRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public List<ReviewSummaryByPost> sumNotDeletedByWriters(Collection<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return List.of();
        }
        NumberExpression<Long> count = salesPostReview.count();
        NumberExpression<Long> ratingSum = salesPostReview.rating.sumLong();
        return queryFactory
                .select(salesPostReview.salesPostId, count, ratingSum)
                .from(salesPostReview)
                .where(salesPostReview.writerMemberId.in(memberIds), salesPostReview.isDeleted.isFalse())
                .groupBy(salesPostReview.salesPostId)
                .fetch().stream()
                .map(row -> new ReviewSummaryByPost(
                        row.get(salesPostReview.salesPostId),
                        row.get(count),
                        row.get(ratingSum) == null ? 0L : row.get(ratingSum)))
                .toList();
    }

    // 실행 전 flush, 실행 뒤 비우기 (이미 불러온 리뷰의 옛 상태를 다시 쓰지 않게)
    @Override
    public int deleteWrittenBy(Collection<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return 0;
        }
        entityManager.flush();
        long changed = queryFactory.update(salesPostReview)
                .set(salesPostReview.isDeleted, true)
                .where(salesPostReview.writerMemberId.in(memberIds), salesPostReview.isDeleted.isFalse())
                .execute();
        entityManager.clear();
        return (int) changed;
    }
}
