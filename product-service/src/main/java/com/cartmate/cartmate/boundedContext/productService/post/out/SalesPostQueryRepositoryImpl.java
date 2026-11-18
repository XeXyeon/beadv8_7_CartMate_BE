package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.QSalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectSalesPostRequest;
import com.cartmate.cartmate.boundedContext.productService.search.domain.SalesPostSearchCondition;
import com.querydsl.core.types.ExpressionUtils;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.cartmate.cartmate.boundedContext.productService.post.domain.QSalesPost.salesPost;

public class SalesPostQueryRepositoryImpl implements SalesPostQueryRepository {
    private final JPAQueryFactory queryFactory;
    private final EntityManager em;

    public SalesPostQueryRepositoryImpl(EntityManager entityManager) {
        this.em = entityManager;
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Page<SalesPost> selectSalesPostAll(SelectSalesPostRequest request, Pageable pageable) {
        List<SalesPost> content = queryFactory
                .selectFrom(salesPost)
                .where(
                        titleContains(request.getTitle()),
                        onSale()
                )
                .orderBy(salesPost.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return PageableExecutionUtils.getPage(content, pageable, () -> countSalesPost(request));
    }

    private long countSalesPost(SelectSalesPostRequest request) {
        Long total = queryFactory
                .select(salesPost.count())
                .from(salesPost)
                .where(
                        titleContains(request.getTitle()),
                        onSale()
                )
                .fetchOne();
        return total == null ? 0L : total;
    }

    @Override
    public Page<SalesPost> selectBySeller(Long sellerId, Pageable pageable) {
        List<SalesPost> content = queryFactory
                .selectFrom(salesPost)
                .where(salesPost.sellerId.eq(sellerId), salesPost.status.ne(SalesPostSaleStatus.DELETED))
                .orderBy(salesPost.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return PageableExecutionUtils.getPage(content, pageable, () -> {
            Long total = queryFactory
                    .select(salesPost.count())
                    .from(salesPost)
                    .where(salesPost.sellerId.eq(sellerId), salesPost.status.ne(SalesPostSaleStatus.DELETED))
                    .fetchOne();
            return total == null ? 0L : total;
        });
    }

    private BooleanExpression titleContains(String title) {
        return title == null || title.isBlank() ? null : salesPost.title.contains(title);
    }

    // 공개 목록(홈 · 검색)은 지금 살 수 있는 판매 중인 글만 보여준다.
    // 판매 중지 · 운영 중지 · 판매 종료 · 등록 중 · 삭제 중은 빠진다(판매자 본인 목록은 selectBySeller가 삭제된 글만 뺀다)
    private BooleanExpression onSale() {
        return salesPost.status.eq(SalesPostSaleStatus.ON_SALE);
    }

    @Override
    public Long changeUpdateStatus(Long salesPostId, Collection<SalesPostUpdateStatus> from,
                                   SalesPostUpdateStatus to, LocalDateTime now) {
        long updated = queryFactory
                .update(salesPost)
                .set(salesPost.updateStatus, to)
                .set(salesPost.updateStatusChangedAt, now)
                .where(salesPost.id.eq(salesPostId), salesPost.updateStatus.in(from))
                .execute();

        em.flush();
        em.clear();
        return updated;
    }

    @Override
    public Long startUpdate(Long salesPostId, Collection<SalesPostUpdateStatus> from, LocalDateTime now) {
        long updated = queryFactory
                .update(salesPost)
                .set(salesPost.updateStatus, SalesPostUpdateStatus.PENDING)
                .set(salesPost.updateStatusChangedAt, now)
                .where(
                        salesPost.id.eq(salesPostId),
                        salesPost.updateStatus.in(from),
                        salesPost.status.notIn(SalesPostSaleStatus.PENDING, SalesPostSaleStatus.DELETING, SalesPostSaleStatus.DELETED)
                )
                .execute();

        em.flush();
        em.clear();
        return updated;
    }

    @Override
    public Long startDelete(Long salesPostId, Collection<SalesPostUpdateStatus> editable) {
        long updated = queryFactory
                .update(salesPost)
                .set(salesPost.status, SalesPostSaleStatus.DELETING)
                // 일괄 update는 @LastModifiedDate가 채워지지 않는다. 삭제 대사 스케줄러가 삭제 시작 시각으로 쓴다
                .set(salesPost.modifyDate, LocalDateTime.now())
                .where(
                        salesPost.id.eq(salesPostId),
                        salesPost.updateStatus.in(editable),
                        salesPost.status.notIn(SalesPostSaleStatus.PENDING, SalesPostSaleStatus.DELETING, SalesPostSaleStatus.DELETED)
                )
                .execute();

        em.flush();
        em.clear();
        return updated;
    }

    @Override
    public Long refreshDeleting(Long salesPostId, LocalDateTime now) {
        long updated = queryFactory
                .update(salesPost)
                .set(salesPost.modifyDate, now)
                .where(
                        salesPost.id.eq(salesPostId),
                        salesPost.status.eq(SalesPostSaleStatus.DELETING)
                )
                .execute();

        em.flush();
        em.clear();
        return updated;
    }

    @Override
    public Page<SalesPost> selectForAdmin(SalesPostSaleStatus status, String keyword, Pageable pageable) {
        List<SalesPost> content = queryFactory
                .selectFrom(salesPost)
                .where(adminVisible(), statusEq(status), titleContains(keyword))
                .orderBy(salesPost.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return PageableExecutionUtils.getPage(content, pageable, () -> {
            Long total = queryFactory
                    .select(salesPost.count())
                    .from(salesPost)
                    .where(adminVisible(), statusEq(status), titleContains(keyword))
                    .fetchOne();
            return total == null ? 0L : total;
        });
    }

    // 관리자는 삭제·등록 진행 중을 제외한 전부를 본다 (숨김 포함)
    private BooleanExpression adminVisible() {
        return salesPost.status.notIn(SalesPostSaleStatus.DELETED, SalesPostSaleStatus.DELETING, SalesPostSaleStatus.PENDING);
    }

    private BooleanExpression statusEq(SalesPostSaleStatus status) {
        return status == null ? null : salesPost.status.eq(status);
    }

    // select status, count(*) from post_sales_post where status in (...) group by status
    @Override
    public Map<SalesPostSaleStatus, Long> countByStatuses(Collection<SalesPostSaleStatus> statuses) {
        NumberExpression<Long> count = salesPost.count();   // select와 결과 꺼내기에 같은 식을 쓴다
                return queryFactory
                        .select(salesPost.status, count)
                        .from(salesPost)
                        .where(salesPost.status.in(statuses))
                        .groupBy(salesPost.status)
                        .fetch()
                        .stream()
                        .collect(Collectors.toMap(
                                row -> row.get(salesPost.status),
                                row -> row.get(count)));
    }

    @Override
    public Optional<SalesPost> findLatestById(Long id) {
        return Optional.ofNullable(queryFactory
                .selectFrom(salesPost)
                .where(salesPost.id.eq(id))
                .setLockMode(LockModeType.PESSIMISTIC_READ)
                .fetchOne());
    }

    @Override
    public List<Long> findIdsBySellerIdAndStatus(Long sellerId, SalesPostSaleStatus status) {
        return queryFactory
                .select(salesPost.id)
                .from(salesPost)
                .where(salesPost.sellerId.eq(sellerId), salesPost.status.eq(status))
                .orderBy(salesPost.id.asc())
                .fetch();
    }

    @Override
    public List<Long> findOnSaleIdsAfterId(Long afterId, int limit) {
        return queryFactory
                .select(salesPost.id)
                .from(salesPost)
                .where(onSale(), salesPost.id.gt(afterId))
                .orderBy(salesPost.id.asc())
                .limit(limit)
                .fetch();
    }

    // @Modifying(flushAutomatically = true, clearAutomatically = true)와 같게: 실행 전 flush, 실행 뒤 비우기
    @Override
    public int applyReviewSummary(Long postId, long countDelta, long ratingDelta) {
        em.flush();
        long changed = queryFactory.update(salesPost)
                .set(salesPost.reviewCount, salesPost.reviewCount.add(countDelta))
                .set(salesPost.reviewRatingSum, salesPost.reviewRatingSum.add(ratingDelta))
                .where(salesPost.id.eq(postId))
                .execute();
        em.clear();
        return (int) changed;
    }

    @Override
    public Page<SalesPost> selectForSearchFallback(SalesPostSearchCondition condition, Pageable pageable) {
        Predicate[] where = {
                onSale(),
                titleContains(condition.keyword()),
                // 검색 색인처럼, 살 수 있는 상품이 없는 글은 뺀다
                onSaleProductExists("anyProduct", product -> null),
                // 카테고리 · 가격은 같은 상품 하나가 모두 맞아야 한다(검색 색인과 같다)
                condition.categoryIds().isEmpty() && condition.minPrice() == null && condition.maxPrice() == null ? null
                        : onSaleProductExists("matchingProduct", product -> ExpressionUtils.allOf(
                                categoryIn(product, condition.categoryIds()),
                                priceBetween(product, condition.minPrice(), condition.maxPrice())))
        };
        List<SalesPost> content = queryFactory
                .selectFrom(salesPost)
                .where(where)
                .orderBy(salesPost.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        return PageableExecutionUtils.getPage(content, pageable, () -> {
            Long total = queryFactory.select(salesPost.count()).from(salesPost).where(where).fetchOne();
            return total == null ? 0L : total;
        });
    }

    private BooleanExpression onSaleProductExists(String alias, Function<QSalesPostProduct, Predicate> condition) {
        QSalesPostProduct product = new QSalesPostProduct(alias);
        return JPAExpressions.selectOne()
                .from(product)
                .where(product.salesPost.eq(salesPost),
                        product.status.eq(SalesPostProductStatus.ON_SALE),
                        condition.apply(product))
                .exists();
    }

    private BooleanExpression categoryIn(QSalesPostProduct product, List<Long> categoryIds) {
        return categoryIds.isEmpty() ? null : product.categoryId.in(categoryIds);
    }

    private BooleanExpression priceBetween(QSalesPostProduct product, Long minPrice, Long maxPrice) {
        BooleanExpression min = minPrice == null ? null : product.basePrice.goe(minPrice);
        BooleanExpression max = maxPrice == null ? null : product.basePrice.loe(maxPrice);
        return min == null ? max : min.and(max);
    }
}
