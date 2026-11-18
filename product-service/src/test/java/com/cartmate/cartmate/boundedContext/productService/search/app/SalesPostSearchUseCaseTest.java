package com.cartmate.cartmate.boundedContext.productService.search.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectSalesPostResponse;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.search.domain.SalesPostSearchCondition;
import com.cartmate.cartmate.boundedContext.productService.search.domain.SalesPostSearchHits;
import com.cartmate.cartmate.boundedContext.productService.search.out.SalesPostSearchClient;
import com.cartmate.cartmate.global.page.PageResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// 검색 색인 응답은 목으로 정하고, DB 조립과 대체 검색은 실제 MySQL로 본다
@SpringBootTest(properties = "cartmate.scheduling.enabled=false")
@ActiveProfiles("test")
class SalesPostSearchUseCaseTest {

    private static final AtomicLong PRODUCT_SEQ = new AtomicLong(9_300_000L);
    private static final Long SELLER_ID = 7L;

    @Autowired SalesPostSearchUseCase searchUseCase;
    @Autowired SalesPostRepository salesPostRepository;
    @Autowired SalesPostProductRepository salesPostProductRepository;
    @Autowired MeterRegistry meterRegistry;
    @Autowired SalesPostIndexHealth indexHealth;
    @MockitoBean SalesPostSearchClient searchClient;

    @BeforeEach
    void setUp() {
        salesPostProductRepository.deleteAll();
        salesPostRepository.deleteAll();
    }

    @Test
    void ES가_정한_순서대로_응답한다() {
        Long a = savePost("가", SalesPostSaleStatus.ON_SALE);
        Long b = savePost("나", SalesPostSaleStatus.ON_SALE);
        Long c = savePost("다", SalesPostSaleStatus.ON_SALE);
        given(searchClient.search(any(), any())).willReturn(new SalesPostSearchHits(List.of(c, a, b), 3));

        PageResponse<SelectSalesPostResponse> page = search("아무거나");

        assertThat(page.content()).extracting(SelectSalesPostResponse::getId).containsExactly(c, a, b);
        assertThat(page.totalElements()).isEqualTo(3);
    }

    @Test
    void ES가_돌려준_id라도_판매_중이_아니면_응답에서_뺀다() {
        Long onSale = savePost("판매 중", SalesPostSaleStatus.ON_SALE);
        Long paused = savePost("방금 중지", SalesPostSaleStatus.PAUSED);
        given(searchClient.search(any(), any())).willReturn(new SalesPostSearchHits(List.of(paused, onSale), 2));

        PageResponse<SelectSalesPostResponse> page = search("아무거나");

        assertThat(page.content()).extracting(SelectSalesPostResponse::getId).containsExactly(onSale);
    }

    @Test
    void ES가_실패하면_MySQL로_최신순_검색하고_지표를_남긴다() {
        Long older = savePost("무선 이어폰", SalesPostSaleStatus.ON_SALE);
        Long newer = savePost("유선 이어폰", SalesPostSaleStatus.ON_SALE);
        savePost("가방", SalesPostSaleStatus.ON_SALE);
        given(searchClient.search(any(), any())).willThrow(new UncheckedIOException(new IOException("연결 실패")));
        double before = fallbackCount("error");
        indexHealth.markFixed(indexHealth.snapshot());

        PageResponse<SelectSalesPostResponse> page = search("이어폰");

        assertThat(page.content()).extracting(SelectSalesPostResponse::getId).containsExactly(newer, older);
        assertThat(fallbackCount("error")).isEqualTo(before + 1);
        // 닿지 않거나 느린 것은 색인이 변경을 놓쳤다는 뜻이 아니라 재색인하지 않는다
        assertThat(indexHealth.isStale()).isFalse();
    }

    @Test
    void 색인이_없어서_검색이_실패하면_낡음으로_표시한다() {
        given(searchClient.search(any(), any())).willThrow(new ElasticsearchException("search",
                ErrorResponse.of(r -> r.status(404).error(e -> e.type("index_not_found_exception").reason("no such index")))));
        indexHealth.markFixed(indexHealth.snapshot());

        search("이어폰");

        assertThat(indexHealth.isStale()).isTrue();
    }

    @Test
    void 대체_검색도_판매_중인_상품의_카테고리와_가격으로_거른다() {
        Long match = savePost("축구화", SalesPostSaleStatus.ON_SALE, product(3L, 40_000L, SalesPostProductStatus.ON_SALE));
        // 조건에 맞는 상품은 지워졌고, 남은 판매 중 상품은 조건에 맞지 않는다
        savePost("축구화 묶음", SalesPostSaleStatus.ON_SALE,
                product(3L, 40_000L, SalesPostProductStatus.DELETED), product(1L, 10_000L, SalesPostProductStatus.ON_SALE));
        given(searchClient.search(any(), any())).willThrow(new UncheckedIOException(new IOException("연결 실패")));

        PageResponse<SelectSalesPostResponse> page = searchUseCase.search(
                new SalesPostSearchCondition(null, List.of(3L), 30_000L, 50_000L, null), PageRequest.of(0, 20));

        assertThat(page.content()).extracting(SelectSalesPostResponse::getId).containsExactly(match);
    }

    private PageResponse<SelectSalesPostResponse> search(String keyword) {
        return searchUseCase.search(new SalesPostSearchCondition(keyword, null, null, null, null), PageRequest.of(0, 20));
    }

    private double fallbackCount(String reason) {
        var counter = meterRegistry.find(SalesPostSearchUseCase.FALLBACK_METRIC).tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    private record ProductSpec(Long categoryId, Long price, SalesPostProductStatus status) {
    }

    private static ProductSpec product(Long categoryId, Long price, SalesPostProductStatus status) {
        return new ProductSpec(categoryId, price, status);
    }

    private Long savePost(String title, SalesPostSaleStatus status, ProductSpec... products) {
        SalesPost post = salesPostRepository.save(new SalesPost(SELLER_ID, title, "내용", status, 0L,
                SalesPostUpdateStatus.NEW, LocalDateTime.now()));
        List<ProductSpec> specs = products.length == 0
                ? List.of(product(1L, 10_000L, SalesPostProductStatus.ON_SALE)) : List.of(products);
        specs.forEach(spec -> salesPostProductRepository.save(new SalesPostProduct(PRODUCT_SEQ.incrementAndGet(), SELLER_ID,
                post, spec.categoryId(), "상품", "설명", spec.price(), spec.status(), LocalDateTime.now(), LocalDateTime.now())));
        return post.getId();
    }
}
