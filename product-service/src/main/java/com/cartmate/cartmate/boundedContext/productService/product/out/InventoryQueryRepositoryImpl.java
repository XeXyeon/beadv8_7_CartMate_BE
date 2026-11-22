package com.cartmate.cartmate.boundedContext.productService.product.out;

import org.springframework.transaction.annotation.Transactional;
import com.cartmate.cartmate.boundedContext.productService.product.domain.ProductStatus;
import com.cartmate.cartmate.boundedContext.productService.product.dto.InventoryStock;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.querydsl.jpa.impl.JPAUpdateClause;
import jakarta.persistence.EntityManager;

import java.util.List;

import static com.cartmate.cartmate.boundedContext.productService.product.domain.QInventory.inventory;
import static com.cartmate.cartmate.boundedContext.productService.product.domain.QProduct.product;

// 예전 @Query 메서드처럼 트랜잭션 밖에서 불러도 동작하게 한다(안에서 부르면 그 트랜잭션에 합류)
@Transactional
public class InventoryQueryRepositoryImpl implements InventoryQueryRepository {
    private final EntityManager entityManager;
    private final JPAQueryFactory queryFactory;

    public InventoryQueryRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InventoryStock> findStocksBySalesPostId(Long salesPostId) {
        return queryFactory
                .select(Projections.constructor(InventoryStock.class,
                        inventory.id, inventory.stockQuantity, inventory.reservedQuantity))
                .from(inventory)
                .join(inventory.product, product)
                .where(product.salesPostId.eq(salesPostId),
                        product.status.ne(ProductStatus.DELETED),
                        inventory.status.eq(ProductStatus.ACTIVE))
                .orderBy(inventory.id.asc())
                .fetch();
    }

    @Override
    public int reserve(Long id, int quantity) {
        return execute(queryFactory.update(inventory)
                .set(inventory.reservedQuantity, inventory.reservedQuantity.add(quantity))
                .where(inventory.id.eq(id),
                        inventory.stockQuantity.subtract(inventory.reservedQuantity).goe(quantity)));
    }

    @Override
    public int release(Long id, int quantity) {
        return execute(queryFactory.update(inventory)
                .set(inventory.reservedQuantity, inventory.reservedQuantity.subtract(quantity))
                .where(inventory.id.eq(id), inventory.reservedQuantity.goe(quantity)));
    }

    @Override
    public int confirm(Long id, int quantity) {
        return execute(queryFactory.update(inventory)
                .set(inventory.stockQuantity, inventory.stockQuantity.subtract(quantity))
                .set(inventory.reservedQuantity, inventory.reservedQuantity.subtract(quantity))
                .where(inventory.id.eq(id),
                        inventory.reservedQuantity.goe(quantity),
                        inventory.stockQuantity.goe(quantity)));
    }

    @Override
    public int restore(Long id, int quantity) {
        return execute(queryFactory.update(inventory)
                .set(inventory.stockQuantity, inventory.stockQuantity.add(quantity))
                .where(inventory.id.eq(id)));
    }

    // @Modifying(flushAutomatically = true, clearAutomatically = true)와 같게: 실행 전 flush, 실행 뒤 비우기
    private int execute(JPAUpdateClause update) {
        entityManager.flush();
        long changed = update.execute();
        entityManager.clear();
        return (int) changed;
    }
}
