package com.cartmate.cartmate.boundedContext.productService.product.out;

import com.cartmate.cartmate.boundedContext.productService.product.domain.Product;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

import static com.cartmate.cartmate.boundedContext.productService.product.domain.QProduct.product;

public class ProductQueryRepositoryImpl implements ProductQueryRepository {
    private final JPAQueryFactory queryFactory;

    public ProductQueryRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Optional<Long> findSalesPostIdById(Long id) {
        return Optional.ofNullable(queryFactory
                .select(product.salesPostId)
                .from(product)
                .where(product.id.eq(id))
                .fetchOne());
    }

    @Override
    public List<Product> findForUpdateBySalesPostId(Long salesPostId) {
        return queryFactory
                .selectFrom(product)
                .where(product.salesPostId.eq(salesPostId))
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .fetch();
    }
}
