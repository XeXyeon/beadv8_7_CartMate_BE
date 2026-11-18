package com.cartmate.cartmate.boundedContext.productService.search.app;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

class SalesPostIndexHealthTest {

    @Test
    void 앱이_막_뜨면_낡음으로_본다() {
        assertThat(new SalesPostIndexHealth().isStale()).isTrue();
    }

    @Test
    void 재색인이_끝나면_낡음이_풀린다() {
        SalesPostIndexHealth health = new SalesPostIndexHealth();
        long snapshot = health.snapshot();

        health.markFixed(snapshot);

        assertThat(health.isStale()).isFalse();
    }

    @Test
    void 재색인_도중_새로_실패하면_끝나도_낡음이_남는다() {
        SalesPostIndexHealth health = new SalesPostIndexHealth();
        long snapshot = health.snapshot();

        health.markStale();
        health.markFixed(snapshot);

        assertThat(health.isStale()).isTrue();
    }

    @Test
    void 연결_실패와_5xx와_색인_없음만_재색인으로_고친다() {
        assertThat(SalesPostIndexHealth.fixableByReindex(new UncheckedIOException(new IOException("연결 실패")))).isTrue();
        assertThat(SalesPostIndexHealth.fixableByReindex(esError(503))).isTrue();
        assertThat(SalesPostIndexHealth.fixableByReindex(esError(404))).isTrue();
        assertThat(SalesPostIndexHealth.fixableByReindex(esError(400))).isFalse();
        assertThat(SalesPostIndexHealth.fixableByReindex(new IllegalStateException("DB 오류"))).isFalse();
    }

    private static ElasticsearchException esError(int status) {
        return new ElasticsearchException("test",
                ErrorResponse.of(r -> r.status(status).error(e -> e.type("test_exception").reason("테스트"))));
    }
}
