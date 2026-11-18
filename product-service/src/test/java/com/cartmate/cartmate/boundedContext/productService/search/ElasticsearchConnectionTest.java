package com.cartmate.cartmate.boundedContext.productService.search;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.AnalyzeResponse;
import co.elastic.clients.elasticsearch.indices.analyze.AnalyzeToken;
import com.cartmate.cartmate.support.ProductServiceElasticsearchContainer;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "cartmate.scheduling.enabled=false")
@ActiveProfiles("test")
@ImportTestcontainers(ProductServiceElasticsearchContainer.class)
class ElasticsearchConnectionTest {

    @Autowired
    ElasticsearchClient client;

    @Test
    void nori_분석기로_붙여쓴_상품명을_쪼갠다() throws IOException {
        AnalyzeResponse res = client.indices().analyze(a -> a
                .tokenizer(t -> t.name("nori_tokenizer")).text("무선블루투스이어폰"));

        assertThat(res.tokens()).extracting(AnalyzeToken::token)
                .contains("무선", "블루투스", "이어폰");
    }
}
