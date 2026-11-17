package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.QSalesPostComment;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostComment;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;

import java.util.List;

import static com.cartmate.cartmate.boundedContext.productService.post.domain.QSalesPostComment.salesPostComment;

public class SalesPostCommentQueryRepositoryImpl implements SalesPostCommentQueryRepository {
    private final EntityManager entityManager;
    private final JPAQueryFactory queryFactory;

    public SalesPostCommentQueryRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Page<SalesPostComment> findVisibleRoots(Long postId, Pageable pageable) {
        List<SalesPostComment> content = queryFactory
                .selectFrom(salesPostComment)
                .where(visibleRootOf(postId))
                .orderBy(salesPostComment.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return PageableExecutionUtils.getPage(content, pageable, () -> {
            Long total = queryFactory
                    .select(salesPostComment.count())
                    .from(salesPostComment)
                    .where(visibleRootOf(postId))
                    .fetchOne();
            return total == null ? 0L : total;
        });
    }

    // @Modifying(clearAutomatically = true)와 같게: 실행 뒤 비우기
    @Override
    public int anonymizeWriter(Long memberId, String nickname) {
        long changed = queryFactory.update(salesPostComment)
                .set(salesPostComment.writerNickname, nickname)
                .where(salesPostComment.writerMemberId.eq(memberId))
                .execute();
        entityManager.clear();
        return (int) changed;
    }

    // 이 판매글의 원댓글 중 살아 있거나, 삭제됐어도 살아 있는 답글이 있는 것
    private BooleanExpression visibleRootOf(Long postId) {
        QSalesPostComment reply = new QSalesPostComment("reply");
        return salesPostComment.salesPostId.eq(postId)
                .and(salesPostComment.parentId.isNull())
                .and(salesPostComment.isDeleted.isFalse()
                        .or(JPAExpressions.selectOne()
                                .from(reply)
                                .where(reply.parentId.eq(salesPostComment.id), reply.isDeleted.isFalse())
                                .exists()));
    }
}
