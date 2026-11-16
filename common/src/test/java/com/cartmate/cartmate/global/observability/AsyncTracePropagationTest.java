package com.cartmate.cartmate.global.observability;

import com.cartmate.cartmate.global.global.AsyncConfig;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// 비동기 실행기(@Async · supplyAsync)로 넘긴 작업이 맡긴 요청과 같은 traceId로 이어지는지
class AsyncTracePropagationTest {

    private final TestTracing tracing = new TestTracing();
    private final ObservationRegistry registry = ObservationRegistry.create();

    @BeforeEach
    void setUp() {
        registry.observationConfig().observationHandler(new DefaultTracingObservationHandler(tracing.tracer));
        ObservationThreadLocalAccessor.getInstance().setObservationRegistry(registry);
    }

    @AfterEach
    void tearDown() {
        ObservationThreadLocalAccessor.getInstance().setObservationRegistry(ObservationRegistry.NOOP);
    }

    @Test
    void eventExecutor_carriesTraceId() throws Exception {
        assertCarriesTraceId(new AsyncConfig().eventExecutor());
    }

    @Test
    void imageUploadExecutor_carriesTraceId() throws Exception {
        assertCarriesTraceId(new AsyncConfig().imageUploadExecutor());
    }

    private void assertCarriesTraceId(ThreadPoolTaskExecutor executor) throws Exception {
        Observation request = Observation.start("http.server.requests", registry);
        try (Observation.Scope ignored = request.openScope()) {
            String requestTraceId = tracing.tracer.currentSpan().context().traceId();

            String workerTraceId = CompletableFuture.supplyAsync(() -> {
                Span span = tracing.tracer.currentSpan();
                return span == null ? null : span.context().traceId();
            }, executor).get(5, TimeUnit.SECONDS);

            assertThat(workerTraceId).isEqualTo(requestTraceId);
        } finally {
            request.stop();
            executor.shutdown();
        }
    }
}
