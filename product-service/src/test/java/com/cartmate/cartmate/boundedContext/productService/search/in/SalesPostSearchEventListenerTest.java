package com.cartmate.cartmate.boundedContext.productService.search.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.search.app.SalesPostIndexHealth;
import com.cartmate.cartmate.boundedContext.productService.search.app.SalesPostIndexer;
import com.cartmate.cartmate.boundedContext.productService.search.out.SalesPostIndexAdmin;
import com.cartmate.cartmate.global.event.EventEnvelope;
import com.cartmate.cartmate.global.event.OutboxMessageRelayed;
import com.cartmate.cartmate.shared.post.event.SalesPostChangedEvent;
import com.cartmate.cartmate.support.ProductServiceElasticsearchContainer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(properties = "cartmate.scheduling.enabled=false")
@ActiveProfiles("test")
@ImportTestcontainers(ProductServiceElasticsearchContainer.class)
class SalesPostSearchEventListenerTest {

    private static final Long SELLER_ID = 7L;

    @Autowired ApplicationEventPublisher applicationEventPublisher;
    @MockitoSpyBean SalesPostIndexer indexer;
    @Autowired SalesPostIndexHealth indexHealth;
    @Autowired SalesPostIndexAdmin indexAdmin;
    @Autowired ElasticsearchClient client;
    @Autowired SalesPostRepository salesPostRepository;
    @Autowired SalesPostProductRepository salesPostProductRepository;

    private String index;

    @BeforeEach
    void setUp() {
        salesPostProductRepository.deleteAll();
        salesPostRepository.deleteAll();
        indexAdmin.currentIndex().ifPresent(indexAdmin::deleteIndex);
        index = indexAdmin.createIndex();
        indexAdmin.swapAlias(index);
    }

    @AfterEach
    void tearDown() {
        indexAdmin.deleteIndex(index);
    }

    @Test
    void 변경_이벤트를_받으면_색인이_DB와_같아진다() throws IOException {
        SalesPost post = salesPostRepository.save(new SalesPost(SELLER_ID, "이어폰 팝니다", "새 제품",
                SalesPostSaleStatus.ON_SALE, 0L, SalesPostUpdateStatus.NEW, LocalDateTime.now()));
        salesPostProductRepository.save(new SalesPostProduct(9_600_001L, SELLER_ID, post, 1L, "무선 이어폰", "설명",
                30_000L, SalesPostProductStatus.ON_SALE, LocalDateTime.now(), LocalDateTime.now()));
        indexer.sync(post.getId());
        assertThat(exists(post.getId())).isTrue();

        post.pauseBySeller();
        salesPostRepository.save(post);
        // 같은 앱 안 릴레이가 보내는 모양 그대로 (트랜잭션 밖, Kafka 아님)
        applicationEventPublisher.publishEvent(relayed(new SalesPostChangedEvent(post.getId())));

        assertThat(exists(post.getId())).isFalse();
    }

    @Test
    void 색인이_실패해도_예외를_던지지_않고_낡음으로_표시한다() {
        indexHealth.markFixed(indexHealth.snapshot());
        doThrow(new UncheckedIOException(new IOException("ES 연결 실패"))).when(indexer).sync(42L);

        // 같은 줄로 오는 다른 이벤트를 막지 않도록 예외를 밖으로 던지지 않는다
        assertThatCode(() -> applicationEventPublisher.publishEvent(relayed(new SalesPostChangedEvent(42L))))
                .doesNotThrowAnyException();
        assertThat(indexHealth.isStale()).isTrue();
    }

    private OutboxMessageRelayed relayed(SalesPostChangedEvent event) {
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID().toString(), "SalesPostChangedEvent",
                "cartmate.post.sales-post-changed", event.aggregateType(), event.aggregateId(), LocalDateTime.now(), 1, "{}");
        return new OutboxMessageRelayed(envelope, event);
    }

    private boolean exists(Long postId) throws IOException {
        return client.exists(e -> e.index(index).id(String.valueOf(postId))).value();
    }
}
