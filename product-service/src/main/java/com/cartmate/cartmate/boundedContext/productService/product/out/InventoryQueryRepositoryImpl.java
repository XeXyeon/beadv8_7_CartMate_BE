package com.cartmate.cartmate.boundedContext.productService.product.out;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.querydsl.jpa.impl.JPAUpdateClause;
import jakarta.persistence.EntityManager;

import static com.cartmate.cartmate.boundedContext.productService.product.domain.QInventory.inventory;

public class InventoryQueryRepositoryImpl implements InventoryQueryRepository {
    private final EntityManager entityManager;
    private final JPAQueryFactory queryFactory;

    public InventoryQueryRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
        this.queryFactory = new JPAQueryFactory(entityManager);
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
