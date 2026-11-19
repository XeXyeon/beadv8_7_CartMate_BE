package com.cartmate.cartmate.boundedContext.productService.search.out;

import static com.cartmate.cartmate.boundedContext.productService.search.out.SalesPostIndexSchema.ALIAS;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortMode;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.cartmate.cartmate.boundedContext.productService.search.domain.SalesPostSearchCondition;
import com.cartmate.cartmate.boundedContext.productService.search.domain.SalesPostSearchHits;
import com.cartmate.cartmate.boundedContext.productService.search.domain.SalesPostSearchSort;
import co.elastic.clients.transport.rest5_client.Rest5ClientOptions;
import co.elastic.clients.transport.rest5_client.low_level.RequestOptions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/** 판매글 검색 색인에서 조건에 맞는 판매글 id를 순서대로 가져온다. 응답 내용은 DB에서 채운다. */
@Component
public class SalesPostSearchClient {

    // 형태소(nori)로 맞추는 필드와 가중치
    private static final List<String> NORI_FIELDS = List.of(
            "title^4", "productNames^3", "content^1", "productDescriptions^1", "categoryNames^0.5");
    // 두 글자 조각으로 맞추는 필드. 형태소로 못 찾는 단어 중간 조각("어폰")을 잡되, 점수는 0.3배로 낮춘다
    private static final List<String> BIGRAM_FIELDS = List.of(
            "title.bigram^4", "productNames.bigram^3", "content.bigram^1",
            "productDescriptions.bigram^1", "categoryNames.bigram^0.5");
    private static final float BIGRAM_BOOST = 0.3f;
    private static final String PRODUCTS = "products";

    private final ElasticsearchClient client;

    public SalesPostSearchClient(ElasticsearchClient client,
                                 @Value("${cartmate.search.timeout:2s}") Duration searchTimeout) {
        this.client = client.withTransportOptions(searchOptions(client, searchTimeout));
    }

    // 검색은 사용자가 기다리므로 짧게 끊고 MySQL로 대신한다. 색인 관리 · 일괄 쓰기는 기본(30초)을 쓴다
    static Rest5ClientOptions searchOptions(ElasticsearchClient client, Duration searchTimeout) {
        Timeout timeout = Timeout.of(searchTimeout);
        Rest5ClientOptions base = Rest5ClientOptions.of(client._transportOptions());
        RequestOptions options = base.restClientRequestOptions().toBuilder()
                .setRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(timeout)
                        .setResponseTimeout(timeout)
                        .build())
                .build();
        return new Rest5ClientOptions(options, base.keepResponseBodyOnException());
    }

    Rest5ClientOptions requestOptions() {
        return (Rest5ClientOptions) client._transportOptions();
    }

    public SalesPostSearchHits search(SalesPostSearchCondition condition, Pageable pageable) {
        try {
            SearchResponse<Void> response = client.search(s -> s
                    .index(ALIAS)
                    .from((int) pageable.getOffset())
                    .size(pageable.getPageSize())
                    .source(src -> src.fetch(false))   // 문서 id가 판매글 id라 본문은 받지 않는다
                    .trackTotalHits(t -> t.enabled(true))
                    .query(q -> q.bool(b -> query(b, condition)))
                    .sort(sorts(condition.sort(), productFilter(condition))), Void.class);
            List<Long> postIds = response.hits().hits().stream().map(hit -> Long.valueOf(hit.id())).toList();
            long total = response.hits().total() == null ? postIds.size() : response.hits().total().value();
            return new SalesPostSearchHits(postIds, total);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private BoolQuery.Builder query(BoolQuery.Builder b, SalesPostSearchCondition condition) {
        if (condition.hasKeyword()) {
            String keyword = condition.keyword();
            // 검색어의 조각이 모두 들어 있어야 걸리되, 조각마다 어느 필드에 있어도 된다(제목 "갤럭시" + 상품명 "이어폰").
            // 형태소 · 두 글자 조각 둘 중 하나로 맞으면 된다
            b.should(sh -> sh.multiMatch(m -> m.query(keyword).fields(NORI_FIELDS)
                            .type(TextQueryType.CrossFields).operator(Operator.And)))
                    .should(sh -> sh.multiMatch(m -> m.query(keyword).fields(BIGRAM_FIELDS)
                            .type(TextQueryType.CrossFields).operator(Operator.And).boost(BIGRAM_BOOST)))
                    .minimumShouldMatch("1");
        } else {
            b.must(m -> m.matchAll(all -> all));
        }
        // 카테고리 · 가격은 같은 상품 하나가 모두 맞아야 걸린다("신발 + 3만~5만"에 신발 10만원 + 양말 4만원은 안 걸린다)
        productFilter(condition).ifPresent(filter ->
                b.filter(f -> f.nested(n -> n.path(PRODUCTS).query(filter))));
        return b;
    }

    // 판매글 안 상품 하나에 거는 카테고리 · 가격 조건. 둘 다 없으면 빈 값
    private static Optional<Query> productFilter(SalesPostSearchCondition condition) {
        List<Query> conditions = new ArrayList<>();
        if (!condition.categoryIds().isEmpty()) {
            List<FieldValue> ids = condition.categoryIds().stream().map(FieldValue::of).toList();
            conditions.add(Query.of(q -> q.terms(t -> t.field(PRODUCTS + ".categoryId").terms(v -> v.value(ids)))));
        }
        if (condition.minPrice() != null || condition.maxPrice() != null) {
            conditions.add(Query.of(q -> q.range(r -> r.number(n -> {
                n.field(PRODUCTS + ".price");
                if (condition.minPrice() != null) {
                    n.gte(condition.minPrice().doubleValue());
                }
                if (condition.maxPrice() != null) {
                    n.lte(condition.maxPrice().doubleValue());
                }
                return n;
            }))));
        }
        return conditions.isEmpty() ? Optional.empty() : Optional.of(Query.of(q -> q.bool(bb -> bb.filter(conditions))));
    }

    private static List<SortOptions> sorts(SalesPostSearchSort sort, Optional<Query> productFilter) {
        return switch (sort) {
            case RELEVANCE -> List.of(score(), field("createdAt", SortOrder.Desc));
            case LATEST -> List.of(field("createdAt", SortOrder.Desc), field("postId", SortOrder.Desc));
            case PRICE_ASC -> List.of(price(SortOrder.Asc, productFilter), field("postId", SortOrder.Desc));
            case PRICE_DESC -> List.of(price(SortOrder.Desc, productFilter), field("postId", SortOrder.Desc));
        };
    }

    // 가격순은 조건에 맞는 상품 중 최저가 기준이다(필터가 없으면 판매 중인 상품 전체의 최저가)
    private static SortOptions price(SortOrder order, Optional<Query> productFilter) {
        return SortOptions.of(s -> s.field(f -> f.field(PRODUCTS + ".price").order(order).mode(SortMode.Min)
                .nested(n -> {
                    n.path(PRODUCTS);
                    productFilter.ifPresent(n::filter);
                    return n;
                })));
    }

    private static SortOptions score() {
        return SortOptions.of(s -> s.score(sc -> sc.order(SortOrder.Desc)));
    }

    private static SortOptions field(String name, SortOrder order) {
        return SortOptions.of(s -> s.field(f -> f.field(name).order(order)));
    }
}
