package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductWishDisplayStatus;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.cartmate.cartmate.boundedContext.productService.post.domain.QSalesPost.salesPost;
import static com.cartmate.cartmate.boundedContext.productService.post.domain.QSalesPostProduct.salesPostProduct;
import static com.cartmate.cartmate.boundedContext.productService.post.domain.QSalesPostProductImage.salesPostProductImage;
import static com.cartmate.cartmate.boundedContext.productService.post.domain.QSalesPostProductWish.salesPostProductWish;

public class SalesPostProductWishQueryRepositoryImpl implements SalesPostProductWishQueryRepository {
    private final JPAQueryFactory queryFactory;

    public SalesPostProductWishQueryRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Page<WishRow> findMyWishes(Long memberId, Pageable pageable) {
        // 찜은 판매글 상품을 id 값으로만 들고 있어 on 조건으로 조인한다
        List<WishRow> content = queryFactory
                .select(Projections.constructor(WishRow.class,
                        salesPostProductWish.id,
                        salesPostProductWish.createDate,
                        salesPostProduct.id,
                        salesPostProduct.name,
                        salesPostProduct.basePrice,
                        salesPostProduct.status,
                        salesPost.id,
                        salesPost.status))
                .from(salesPostProductWish)
                .join(salesPostProduct).on(salesPostProduct.id.eq(salesPostProductWish.salesPostProductId))
                .join(salesPostProduct.salesPost, salesPost)
                .where(listedWishOf(memberId))
                .orderBy(salesPostProductWish.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return PageableExecutionUtils.getPage(content, pageable, () -> {
            Long total = queryFactory
                    .select(salesPostProductWish.count())
                    .from(salesPostProductWish)
                    .join(salesPostProduct).on(salesPostProduct.id.eq(salesPostProductWish.salesPostProductId))
                    .join(salesPostProduct.salesPost, salesPost)
                    .where(listedWishOf(memberId))
                    .fetchOne();
            return total == null ? 0L : total;
        });
    }

    @Override
    public Map<Long, String> findThumbnailS3Keys(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        // 상품마다 이미지는 몇 장뿐이라, 활성 이미지를 순서대로 한 번에 받아 상품마다 첫 장만 남긴다
        List<Tuple> images = queryFactory
                .select(salesPostProductImage.salesPostProduct.id, salesPostProductImage.s3Key)
                .from(salesPostProductImage)
                .where(salesPostProductImage.salesPostProduct.id.in(productIds),
                        salesPostProductImage.status.eq(SalesPostProductStatus.ACTIVE))
                .orderBy(salesPostProductImage.imageSeq.asc(), salesPostProductImage.id.asc())
                .fetch();

        Map<Long, String> thumbnails = new HashMap<>();
        for (Tuple image : images) {
            thumbnails.putIfAbsent(image.get(salesPostProductImage.salesPostProduct.id),
                    image.get(salesPostProductImage.s3Key));
        }
        return thumbnails;
    }

    // 이 회원의 찜 중 목록에 보일 것
    private BooleanExpression listedWishOf(Long memberId) {
        return salesPostProductWish.memberId.eq(memberId)
                .and(salesPostProduct.status.notIn(SalesPostProductWishDisplayStatus.EXCLUDED_PRODUCT_STATUSES))
                .and(salesPost.status.notIn(SalesPostProductWishDisplayStatus.EXCLUDED_POST_STATUSES));
    }
}
