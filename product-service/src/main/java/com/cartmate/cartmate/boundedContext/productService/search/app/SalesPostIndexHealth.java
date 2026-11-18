package com.cartmate.cartmate.boundedContext.productService.search.app;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 검색 색인이 DB보다 뒤처졌을 수 있는지 기억한다. 낡았으면 복구 스케줄러가 ES가 살아 있을 때 전체 재색인한다.
 *
 * <p>앱이 막 떴을 때는 꺼져 있던 동안 놓친 변경이 있을 수 있어 낡음으로 시작한다.
 * 메모리에만 두므로 파드마다 따로 기억한다(지금은 파드 하나).
 */
@Component
public class SalesPostIndexHealth {

    // 낡음 표시를 할 때마다 1씩 늘린다. 재색인은 시작할 때 본 값까지만 해결한 것으로 친다
    private final AtomicLong staleMarks = new AtomicLong(1);
    private long fixedUpTo = 0;

    public void markStale() {
        staleMarks.incrementAndGet();
    }

    public synchronized boolean isStale() {
        return staleMarks.get() > fixedUpTo;
    }

    /** 재색인을 시작할 때 부른다. 끝나면 이 값을 {@link #markFixed(long)}에 넘긴다. */
    public long snapshot() {
        return staleMarks.get();
    }

    /** 재색인 도중에 새로 실패했다면 그 표시는 남아 다음 복구 때 다시 재색인한다. */
    public synchronized void markFixed(long snapshot) {
        fixedUpTo = Math.max(fixedUpTo, snapshot);
    }

    /**
     * ES에 닿지 못했거나(연결 실패 · 타임아웃) ES가 응답할 수 없었거나(5xx) 색인이 없을 때(404)만 재색인으로 고쳐진다.
     * 문서 내용 오류(그 밖의 4xx)는 재색인해도 같은 데서 실패하므로 낡음으로 보지 않는다.
     */
    public static boolean fixableByReindex(RuntimeException e) {
        if (e instanceof UncheckedIOException) {
            return true;
        }
        if (e instanceof ElasticsearchException es) {
            return es.status() >= 500 || es.status() == 404;
        }
        return false;
    }
}
