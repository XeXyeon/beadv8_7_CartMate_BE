package com.cartmate.cartmate.boundedContext.productService.search.app;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostSelectUseCase;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectSalesPostResponse;
import com.cartmate.cartmate.boundedContext.productService.search.domain.SalesPostSearchCondition;
import com.cartmate.cartmate.boundedContext.productService.search.domain.SalesPostSearchHits;
import com.cartmate.cartmate.boundedContext.productService.search.out.SalesPostSearchClient;
import com.cartmate.cartmate.global.page.PageResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.UncheckedIOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/** 판매글 검색. 검색 색인으로 id와 순서를 정하고 내용은 DB에서 채운다. 색인을 못 쓰면 MySQL로 대신 찾는다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesPostSearchUseCase {

    // 프로메테우스로 나가면 sales_post_search_fallback_total
    static final String FALLBACK_METRIC = "sales.post.search.fallback";

    private final SalesPostSearchClient searchClient;
    private final SalesPostSelectUseCase selectUseCase;
    private final MeterRegistry meterRegistry;
    private final SalesPostIndexHealth indexHealth;

    // 트랜잭션을 걸지 않는다. ES를 기다리는 동안(최대 1초) DB 커넥션을 붙잡지 않도록, DB 읽기는 SalesPostSelectUseCase가 따로 트랜잭션을 연다
    public PageResponse<SelectSalesPostResponse> search(SalesPostSearchCondition condition, Pageable pageable) {
        SalesPostSearchHits hits;
        try {
            hits = searchClient.search(condition, pageable);
        } catch (UncheckedIOException | ElasticsearchException e) {
            String reason = isTimeout(e) ? "timeout" : "error";
            log.warn("판매글 검색 색인을 쓰지 못해 MySQL로 대신 검색합니다. reason={}", reason, e);
            meterRegistry.counter(FALLBACK_METRIC, "reason", reason).increment();
            // 검색이 느리거나 닿지 않는 것은 색인이 변경을 놓쳤다는 뜻이 아니다. 색인 · 별칭이 없을 때(404)만 재색인한다
            if (e instanceof ElasticsearchException es && es.status() == 404) {
                indexHealth.markStale();
            }
            return selectUseCase.selectForSearchFallback(condition, pageable);
        }
        List<SelectSalesPostResponse> content = selectUseCase.selectOnSaleInOrder(hits.postIds());
        // 색인 반영 전에 판매 중이 아니게 된 글을 뺀 만큼 전체 건수도 줄인다(다른 페이지의 낡은 글까지는 모른다)
        long total = Math.max(0, hits.total() - (hits.postIds().size() - content.size()));
        int size = pageable.getPageSize();
        int totalPages = (int) ((total + size - 1) / size);
        return new PageResponse<>(content, pageable.getPageNumber(), size, total, totalPages);
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
        }
        return false;
    }
}
