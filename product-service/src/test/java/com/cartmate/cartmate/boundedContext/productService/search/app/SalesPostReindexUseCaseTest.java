package com.cartmate.cartmate.boundedContext.productService.search.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.search.out.SalesPostIndexAdmin;
import com.cartmate.cartmate.support.ProductServiceElasticsearchContainer;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(properties = "cartmate.scheduling.enabled=false")
@ActiveProfiles("test")
@ImportTestcontainers(ProductServiceElasticsearchContainer.class)
class SalesPostReindexUseCaseTest {

    private static final AtomicLong PRODUCT_SEQ = new AtomicLong(9_200_000L);
    private static final Long SELLER_ID = 7L;

    @Autowired SalesPostReindexUseCase reindexUseCase;
    @Autowired SalesPostIndexAdmin indexAdmin;
    @Autowired ElasticsearchClient client;
    @Autowired SalesPostRepository salesPostRepository;
    @Autowired SalesPostProductRepository salesPostProductRepository;
    @MockitoSpyBean SalesPostIndexer indexer;

    @BeforeEach
    @AfterEach
    void clean() {
        indexAdmin.currentIndex().ifPresent(indexAdmin::deleteIndex);
        indexAdmin.reindexingIndex().ifPresent(indexAdmin::deleteIndex);
        salesPostProductRepository.deleteAll();
        salesPostRepository.deleteAll();
    }

    @Test
    void 재색인하면_판매_중인_판매글만_새_색인에_들어가고_별칭이_옮겨진다() throws IOException {
        Long onSale = savePost(SalesPostSaleStatus.ON_SALE);
        Long paused = savePost(SalesPostSaleStatus.PAUSED);

        String index = reindexUseCase.reindex();

        assertThat(indexAdmin.currentIndex()).contains(index);
        assertThat(indexAdmin.reindexingIndex()).isEmpty();
        assertThat(exists(index, onSale)).isTrue();
        assertThat(exists(index, paused)).isFalse();
    }

    @Test
    void 재색인_뒤_옛_색인은_지워진다() throws IOException {
        String old = reindexUseCase.reindex();

        reindexUseCase.reindex();

        assertThat(client.indices().exists(e -> e.index(old)).value()).isFalse();
    }

    @Test
    void 재색인_중_바뀐_판매글도_새_색인에_반영된다() throws IOException {
        savePost(SalesPostSaleStatus.ON_SALE);
        Long resumed = savePost(SalesPostSaleStatus.PAUSED);
        // 일괄 반영 도중 판매자가 판매를 다시 열고, 그 변경 이벤트로 sync가 도는 상황
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            SalesPost post = salesPostRepository.findById(resumed).orElseThrow();
            post.resumeBySeller();
            salesPostRepository.save(post);
            indexer.sync(resumed);
            return result;
        }).when(indexer).bulkIndex(anyString(), anyList());

        String index = reindexUseCase.reindex();

        assertThat(exists(index, resumed)).isTrue();
    }

    @Test
    void 재색인이_실패하면_새_색인을_지우고_검색_별칭은_옛_색인에_남는다() throws IOException {
        String old = reindexUseCase.reindex();
        savePost(SalesPostSaleStatus.ON_SALE);
        doThrow(new IllegalStateException("bulk 실패")).when(indexer).bulkIndex(anyString(), anyList());

        assertThatThrownBy(() -> reindexUseCase.reindex()).isInstanceOf(IllegalStateException.class);

        assertThat(indexAdmin.currentIndex()).contains(old);
        assertThat(indexAdmin.reindexingIndex()).isEmpty();
        assertThat(client.indices().get(g -> g.index("sales-posts-*")).indices()).containsOnlyKeys(old);
    }

    private Long savePost(SalesPostSaleStatus status) {
        SalesPost post = salesPostRepository.save(new SalesPost(SELLER_ID, "이어폰 팝니다", "새 제품", status, 0L,
                SalesPostUpdateStatus.NEW, LocalDateTime.now()));
        salesPostProductRepository.save(new SalesPostProduct(PRODUCT_SEQ.incrementAndGet(), SELLER_ID, post, 1L,
                "무선 이어폰", "설명", 30_000L, SalesPostProductStatus.ON_SALE, LocalDateTime.now(), LocalDateTime.now()));
        return post.getId();
    }

    private boolean exists(String index, Long postId) throws IOException {
        return client.exists(e -> e.index(index).id(String.valueOf(postId))).value();
    }
}
