package com.cartmate.cartmate.global.global;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.ClassUtils;

@Configuration
@EnableAsync
public class AsyncConfig {

    private static final boolean CONTEXT_PROPAGATION_PRESENT =
            ClassUtils.isPresent("io.micrometer.context.ContextSnapshotFactory", AsyncConfig.class.getClassLoader());

    @Bean(name = "imageUploadExecutor")
    public ThreadPoolTaskExecutor imageUploadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(30);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("image-upload-");
        propagateContext(executor);
        executor.initialize();
        return executor;
    }

    // 커밋 후 처리하는 도메인 이벤트용 (판매글 숨김 후처리 등).
    // 처리 중 서버가 꺼지면 큐에 있던 작업은 사라지므로, 대사 스케줄러가 다시 맞춘다.
    @Bean(name = "eventExecutor")
    public ThreadPoolTaskExecutor eventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("event-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        propagateContext(executor);
        executor.initialize();
        return executor;
    }

    // 맡긴 스레드의 현재 trace(span · 로그 MDC traceId)를 작업 스레드로 넘긴다 → @Async · supplyAsync 작업도 같은 traceId로 이어진다.
    // context-propagation이 없는 서비스(추적 스타터 없음)에서는 아무것도 하지 않는다
    static void propagateContext(ThreadPoolTaskExecutor executor) {
        if (CONTEXT_PROPAGATION_PRESENT) {
            executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        }
    }
}
