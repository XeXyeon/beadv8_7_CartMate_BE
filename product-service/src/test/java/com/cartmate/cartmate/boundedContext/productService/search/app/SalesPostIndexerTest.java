package com.cartmate.cartmate.boundedContext.productService.search.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.search.domain.SalesPostDocument;
import com.cartmate.cartmate.boundedContext.productService.search.out.SalesPostIndexAdmin;
import com.cartmate.cartmate.support.ProductServiceElasticsearchContainer;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "cartmate.scheduling.enabled=false")
@ActiveProfiles("test")
@ImportTestcontainers(ProductServiceElasticsearchContainer.class)
class SalesPostIndexerTest {

    private static final AtomicLong PRODUCT_SEQ = new AtomicLong(9_100_000L);
    private static final Long SELLER_ID = 7L;

    @Autowired SalesPostIndexer indexer;
    @Autowired SalesPostIndexAdmin indexAdmin;
    @Autowired ElasticsearchClient client;
    @Autowired SalesPostRepository salesPostRepository;
    @Autowired SalesPostProductRepository salesPostProductRepository;

    private final List<String> createdIndices = new ArrayList<>();
    private String current;

    @BeforeEach
    void setUp() {
        salesPostProductRepository.deleteAll();
        salesPostRepository.deleteAll();
        current = createIndex();
        indexAdmin.swapAlias(current);
    }

    @AfterEach
    void tearDown() {
        createdIndices.forEach(indexAdmin::deleteIndex);
    }

    @Test
    void 판매_중인_판매글은_문서가_생긴다() throws IOException {
        Long postId = savePost(SalesPostSaleStatus.ON_SALE);

        indexer.sync(postId);

        GetResponse<SalesPostDocument> doc = get(current, postId);
        assertThat(doc.found()).isTrue();
        assertThat(doc.source().productNames()).containsExactly("무선 이어폰");
        assertThat(doc.source().categoryNames()).containsExactly("유니폼 / 의류");
    }

    @Test
    void 판매_중지된_판매글은_문서가_지워진다() throws IOException {
        Long postId = savePost(SalesPostSaleStatus.ON_SALE);
        indexer.sync(postId);

        SalesPost post = salesPostRepository.findById(postId).orElseThrow();
        post.pauseBySeller();
        salesPostRepository.save(post);
        indexer.sync(postId);

        assertThat(get(current, postId).found()).isFalse();
    }

    @Test
    void 없는_판매글을_sync해도_예외가_없다() {
        assertThatCode(() -> indexer.sync(999_999L)).doesNotThrowAnyException();
    }

    @Test
    void 재색인_중이면_두_색인에_모두_쓴다() throws IOException {
        String next = createIndex();
        indexAdmin.markReindexing(next);
        Long postId = savePost(SalesPostSaleStatus.ON_SALE);

        indexer.sync(postId);

        assertThat(get(current, postId).found()).isTrue();
        assertThat(get(next, postId).found()).isTrue();
    }

    @Test
    void 일괄_반영은_판매_중인_판매글만_넣는다() throws IOException {
        Long onSale = savePost(SalesPostSaleStatus.ON_SALE);
        Long paused = savePost(SalesPostSaleStatus.PAUSED);

        indexer.bulkIndex(current, List.of(onSale, paused));

        assertThat(get(current, onSale).found()).isTrue();
        assertThat(get(current, paused).found()).isFalse();
    }

    private Long savePost(SalesPostSaleStatus status) {
        SalesPost post = salesPostRepository.save(new SalesPost(SELLER_ID, "이어폰 팝니다", "새 제품", status, 0L,
                SalesPostUpdateStatus.NEW, LocalDateTime.now()));
        salesPostProductRepository.save(new SalesPostProduct(PRODUCT_SEQ.incrementAndGet(), SELLER_ID, post, 1L,
                "무선 이어폰", "설명", 30_000L, SalesPostProductStatus.ON_SALE, LocalDateTime.now(), LocalDateTime.now()));
        return post.getId();
    }

    private GetResponse<SalesPostDocument> get(String index, Long postId) throws IOException {
        return client.get(g -> g.index(index).id(String.valueOf(postId)), SalesPostDocument.class);
    }

    private String createIndex() {
        String index = indexAdmin.createIndex();
        createdIndices.add(index);
        return index;
    }
}
