package com.cartmate.cartmate.boundedContext.productService.product.out;

import org.springframework.transaction.annotation.Transactional;
import com.cartmate.cartmate.boundedContext.productService.product.domain.Product;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

import static com.cartmate.cartmate.boundedContext.productService.product.domain.QProduct.product;

// 예전 @Query 메서드처럼 트랜잭션 밖에서 불러도 동작하게 한다(안에서 부르면 그 트랜잭션에 합류)
@Transactional
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
