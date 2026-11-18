package com.cartmate.cartmate.boundedContext.productService.search.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willDoNothing;

import com.cartmate.cartmate.boundedContext.productService.post.app.AdminSalesPostUseCase;
import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostCreateUseCase;
import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostDeleteUseCase;
import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostFacade;
import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostHideUseCase;
import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostSyncProductUseCase;
import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostUpdateUseCase;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SyncProduct;
import com.cartmate.cartmate.boundedContext.productService.post.dto.UpdateSalesPostRequest;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.global.outbox.OutboxEventRepository;
import com.cartmate.cartmate.shared.product.event.ProductDeletedEvent;
import com.cartmate.cartmate.support.ProductServiceTestSupport;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// 판매글이 바뀌는 길마다 같은 트랜잭션으로 outbox에 SalesPostChangedEvent가 남는지 본다
class SalesPostChangedEventPublishTest extends ProductServiceTestSupport {

    private static final AtomicLong PRODUCT_SEQ = new AtomicLong(9_500_000L);
    private static final Long SELLER_ID = 7L;
    private static final Long ADMIN_ID = 99L;

    @Autowired SalesPostCreateUseCase createUseCase;
    @Autowired SalesPostUpdateUseCase updateUseCase;
    @Autowired SalesPostSyncProductUseCase syncProductUseCase;
    @Autowired SalesPostHideUseCase hideUseCase;
    @Autowired AdminSalesPostUseCase adminUseCase;
    @Autowired SalesPostDeleteUseCase deleteUseCase;
    @Autowired SalesPostFacade facade;
    @Autowired SalesPostRepository salesPostRepository;
    @Autowired SalesPostProductRepository salesPostProductRepository;
    @Autowired OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        salesPostProductRepository.deleteAll();
        salesPostRepository.deleteAll();
        // 판매 중지 · 운영 중지는 상품 서버 API를 먼저 부른다. 이 테스트는 판매글만 저장하므로 상품 API는 성공한 것으로 정한다
        willDoNothing().given(salesPostApiClient).changeProductStatus(any(), any());
    }

    @Test
    void 등록_완료하면_변경_이벤트가_남는다() {
        Long postId = savePost(SalesPostSaleStatus.PENDING);

        createUseCase.completeSave(postId);

        assertThat(changedEvents(postId)).isEqualTo(1);
    }

    @Test
    void 수정하면_변경_이벤트가_남는다() {
        Long postId = savePost(SalesPostSaleStatus.ON_SALE);

        updateUseCase.updateSalesPostByUser(new UpdateSalesPostRequest(postId, SELLER_ID, "새 제목", "새 본문", List.of()));

        assertThat(changedEvents(postId)).isEqualTo(1);
    }

    @Test
    void 상품_동기화하면_변경_이벤트가_남는다() {
        Long postId = savePost(SalesPostSaleStatus.ON_SALE);
        LocalDateTime now = LocalDateTime.now();

        syncProductUseCase.syncProduct(List.of(new SyncProduct(PRODUCT_SEQ.incrementAndGet(), SELLER_ID, postId, 1L,
                "상품", "설명", 10_000L, now, now, null, null, new ArrayList<>())));

        assertThat(changedEvents(postId)).isEqualTo(1);
    }

    @Test
    void 판매_중지와_재개는_각각_변경_이벤트가_남는다() {
        Long postId = savePost(SalesPostSaleStatus.ON_SALE);

        hideUseCase.pauseBySeller(postId, SELLER_ID, SELLER_ID);
        assertThat(changedEvents(postId)).isEqualTo(1);

        hideUseCase.resumeBySeller(postId, SELLER_ID);
        assertThat(changedEvents(postId)).isEqualTo(2);
    }

    @Test
    void 운영_중지와_해제는_각각_변경_이벤트가_남는다() {
        Long postId = savePost(SalesPostSaleStatus.ON_SALE);

        adminUseCase.suspend(postId, ADMIN_ID, "허위 매물");
        assertThat(changedEvents(postId)).isEqualTo(1);

        adminUseCase.resume(postId, ADMIN_ID, "소명 확인");
        assertThat(changedEvents(postId)).isEqualTo(2);
    }

    @Test
    void 삭제_시작과_완료는_각각_변경_이벤트가_남는다() {
        Long postId = savePost(SalesPostSaleStatus.ON_SALE);

        deleteUseCase.deleteSalesPostByUser(postId, SELLER_ID);
        assertThat(changedEvents(postId)).isEqualTo(1);

        deleteUseCase.completeDelete(postId);
        assertThat(changedEvents(postId)).isEqualTo(2);
    }

    @Test
    void 상품_하나_삭제도_변경_이벤트가_남는다() {
        Long postId = savePost(SalesPostSaleStatus.ON_SALE);
        Long productId = salesPostProductRepository.findBySalesPostId(postId).getFirst().getId();

        facade.deleteProductByEvent(UUID.randomUUID().toString(), new ProductDeletedEvent(productId, postId));

        assertThat(changedEvents(postId)).isEqualTo(1);
    }

    private long changedEvents(Long postId) {
        return outboxEventRepository.findAll().stream()
                .filter(e -> "SalesPostChangedEvent".equals(e.getEventType()))
                .filter(e -> String.valueOf(postId).equals(e.getAggregateId()))
                .count();
    }

    private Long savePost(SalesPostSaleStatus status) {
        SalesPost post = salesPostRepository.save(new SalesPost(SELLER_ID, "이어폰 팝니다", "새 제품", status, 0L,
                SalesPostUpdateStatus.NEW, LocalDateTime.now()));
        salesPostProductRepository.save(new SalesPostProduct(PRODUCT_SEQ.incrementAndGet(), SELLER_ID, post, 1L,
                "무선 이어폰", "설명", 30_000L, SalesPostProductStatus.ON_SALE, LocalDateTime.now(), LocalDateTime.now()));
        return post.getId();
    }
}
