package com.cartmate.cartmate.boundedContext.productService.search.in;

import static org.springframework.transaction.event.TransactionPhase.BEFORE_COMMIT;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.cartmate.cartmate.boundedContext.productService.search.app.SalesPostIndexHealth;
import com.cartmate.cartmate.boundedContext.productService.search.app.SalesPostIndexer;
import com.cartmate.cartmate.global.event.OutboxMessageRelayed;
import com.cartmate.cartmate.shared.post.event.SalesPostChangedEvent;
import java.io.UncheckedIOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** 판매글이 바뀌었다는 이벤트를 받아 검색 색인을 DB 최신 상태로 맞춘다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesPostSearchEventListener {

    private final SalesPostIndexer indexer;
    private final SalesPostIndexHealth indexHealth;

    // ES 실패는 다시 던지지 않는다. 같은 줄로 오는 판매글 · 상품 이벤트(삭제 흐름 등)가 ES 장애 동안 막히지 않게 하고,
    // 놓친 변경은 ES가 살아난 뒤 복구 스케줄러의 전체 재색인으로 맞춘다
    @TransactionalEventListener(phase = BEFORE_COMMIT, fallbackExecution = true,
            condition = "@relayedEventRouting.accepts(#message)")
    public void handle(OutboxMessageRelayed message) {
        if (!(message.event() instanceof SalesPostChangedEvent event)) {
            return;
        }
        try {
            indexer.sync(event.postId());
        } catch (UncheckedIOException | ElasticsearchException e) {
            boolean fixable = SalesPostIndexHealth.fixableByReindex(e);
            if (fixable) {
                indexHealth.markStale();
            }
            log.warn("판매글 검색 색인 반영 실패: postId={}, 재색인으로 복구={}", event.postId(), fixable, e);
        }
    }
}
