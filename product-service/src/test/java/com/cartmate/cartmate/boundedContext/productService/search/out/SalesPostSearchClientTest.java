package com.cartmate.cartmate.boundedContext.productService.search.out;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.cartmate.cartmate.boundedContext.productService.search.domain.SalesPostDocument;
import com.cartmate.cartmate.boundedContext.productService.search.domain.SalesPostSearchCondition;
import com.cartmate.cartmate.boundedContext.productService.search.domain.SalesPostSearchHits;
import com.cartmate.cartmate.boundedContext.productService.search.domain.SalesPostSearchSort;
import com.cartmate.cartmate.support.ProductServiceElasticsearchContainer;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "cartmate.scheduling.enabled=false")
@ActiveProfiles("test")
@ImportTestcontainers(ProductServiceElasticsearchContainer.class)
class SalesPostSearchClientTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 11, 1, 12, 0);

    @Autowired SalesPostSearchClient searchClient;
    @Autowired SalesPostIndexAdmin indexAdmin;
    @Autowired ElasticsearchClient client;

    private String index;

    @BeforeEach
    void setUp() {
        indexAdmin.currentIndex().ifPresent(indexAdmin::deleteIndex);
        index = indexAdmin.createIndex();
        indexAdmin.swapAlias(index);
    }

    @AfterEach
    void tearDown() {
        indexAdmin.deleteIndex(index);
    }

    @Test
    void 붙여_쓴_상품명을_형태소로_찾는다() throws IOException {
        save(doc(1L, "음향기기 정리", "무선블루투스이어폰"));
        save(doc(2L, "운동화 팝니다", "나이키 운동화"));

        assertThat(search("블루투스 이어폰").postIds()).containsExactly(1L);
    }

    @Test
    void 단어_중간_조각으로도_찾는다() throws IOException {
        save(doc(1L, "음향기기 정리", "무선블루투스이어폰"));

        assertThat(search("어폰").postIds()).containsExactly(1L);
    }

    @Test
    void 조각이_모두_있어야_걸린다() throws IOException {
        save(doc(1L, "가방", "가방"));

        assertThat(search("가방끈").total()).isZero();
    }

    @Test
    void 제목에_있는_글이_설명에만_있는_글보다_위다() throws IOException {
        save(new SalesPostDocument(1L, 7L, "중고 물품", "축구공 포함", List.of("물건"), List.of(),
                List.of(1L), List.of(), List.of(10_000L), 10_000L, BASE_TIME));
        save(new SalesPostDocument(2L, 7L, "축구공 팝니다", "새것", List.of("물건"), List.of(),
                List.of(1L), List.of(), List.of(10_000L), 10_000L, BASE_TIME));

        assertThat(search("축구공").postIds()).containsExactly(2L, 1L);
    }

    @Test
    void 카테고리는_상품_중_하나만_맞아도_걸린다() throws IOException {
        save(withCategoryAndPrices(1L, List.of(1L, 3L), List.of(10_000L)));
        save(withCategoryAndPrices(2L, List.of(2L), List.of(10_000L)));

        var hits = searchClient.search(new SalesPostSearchCondition(null, List.of(3L), null, null, null), PageRequest.of(0, 20));

        assertThat(hits.postIds()).containsExactly(1L);
    }

    @Test
    void 가격은_상품_중_하나만_범위에_들어도_걸린다() throws IOException {
        save(withCategoryAndPrices(1L, List.of(1L), List.of(10_000L, 50_000L)));
        save(withCategoryAndPrices(2L, List.of(1L), List.of(70_000L)));

        var hits = searchClient.search(new SalesPostSearchCondition(null, null, 40_000L, 60_000L, null), PageRequest.of(0, 20));

        assertThat(hits.postIds()).containsExactly(1L);
    }

    @Test
    void 낮은_가격순은_최저가_기준이다() throws IOException {
        save(withCategoryAndPrices(1L, List.of(1L), List.of(5_000L, 90_000L)));
        save(withCategoryAndPrices(2L, List.of(1L), List.of(10_000L)));

        var hits = searchClient.search(
                new SalesPostSearchCondition(null, null, null, null, SalesPostSearchSort.PRICE_ASC), PageRequest.of(0, 20));

        assertThat(hits.postIds()).containsExactly(1L, 2L);
    }

    @Test
    void 검색어가_없으면_최신순이다() throws IOException {
        save(new SalesPostDocument(1L, 7L, "예전 글", "", List.of("물건"), List.of(),
                List.of(1L), List.of(), List.of(10_000L), 10_000L, BASE_TIME));
        save(new SalesPostDocument(2L, 7L, "새 글", "", List.of("물건"), List.of(),
                List.of(1L), List.of(), List.of(10_000L), 10_000L, BASE_TIME.plusDays(1)));

        var condition = new SalesPostSearchCondition(null, null, null, null, null);
        var hits = searchClient.search(condition, PageRequest.of(0, 20));

        assertThat(condition.sort()).isEqualTo(SalesPostSearchSort.LATEST);
        assertThat(hits.postIds()).containsExactly(2L, 1L);
    }

    @Test
    void 기호만_있는_검색어는_빈_결과다() throws IOException {
        save(doc(1L, "음향기기 정리", "무선블루투스이어폰"));

        SalesPostSearchHits hits = search("!!");

        assertThat(hits.total()).isZero();
        assertThat(hits.postIds()).isEmpty();
    }

    private SalesPostSearchHits search(String keyword) {
        return searchClient.search(new SalesPostSearchCondition(keyword, null, null, null, null), PageRequest.of(0, 20));
    }

    private SalesPostDocument doc(Long postId, String title, String productName) {
        return new SalesPostDocument(postId, 7L, title, "", List.of(productName), List.of(),
                List.of(1L), List.of("유니폼 / 의류"), List.of(10_000L), 10_000L, BASE_TIME);
    }

    private SalesPostDocument withCategoryAndPrices(Long postId, List<Long> categoryIds, List<Long> prices) {
        return new SalesPostDocument(postId, 7L, "판매글 " + postId, "", List.of("물건"), List.of(),
                categoryIds, List.of(), prices, Collections.min(prices), BASE_TIME);
    }

    private void save(SalesPostDocument document) throws IOException {
        client.index(i -> i.index(index).id(String.valueOf(document.postId())).document(document));
        client.indices().refresh(r -> r.index(index));
    }
}
