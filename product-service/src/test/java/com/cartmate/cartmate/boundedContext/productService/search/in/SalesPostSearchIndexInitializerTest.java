package com.cartmate.cartmate.boundedContext.productService.search.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.cartmate.cartmate.boundedContext.productService.search.out.SalesPostIndexAdmin;
import com.cartmate.cartmate.support.ProductServiceElasticsearchContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.ActiveProfiles;

class SalesPostSearchIndexInitializerTest {

    @Nested
    @SpringBootTest(properties = {"cartmate.scheduling.enabled=false", "cartmate.search.init.enabled=true"})
    @ActiveProfiles("test")
    @ImportTestcontainers(ProductServiceElasticsearchContainer.class)
    class ES가_있을_때 {

        @Autowired SalesPostSearchIndexInitializer initializer;
        @Autowired SalesPostIndexAdmin indexAdmin;

        @AfterEach
        void clean() {
            indexAdmin.currentIndex().ifPresent(indexAdmin::deleteIndex);
        }

        @Test
        void 별칭이_없으면_시작할_때_색인을_만든다() {
            indexAdmin.currentIndex().ifPresent(indexAdmin::deleteIndex);

            initializer.createIndexIfMissing();

            assertThat(indexAdmin.currentIndex()).isPresent();
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "cartmate.scheduling.enabled=false",
            "cartmate.search.init.enabled=true",
            "spring.elasticsearch.uris=http://localhost:1"})   // 아무도 듣지 않는 주소
    @ActiveProfiles("test")
    class ES가_없을_때 {

        @Autowired SalesPostSearchIndexInitializer initializer;

        @Test
        void ES가_없으면_시작_색인_생성은_실패를_기록하고_넘어간다() {
            // 여기까지 왔으면 앱 시작 때 이미 한 번 실패하고도 컨텍스트가 떴다
            assertThatCode(() -> initializer.createIndexIfMissing()).doesNotThrowAnyException();
        }
    }
}
