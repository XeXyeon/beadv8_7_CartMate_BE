package com.cartmate.cartmate.boundedContext.productService.search.in;

import com.cartmate.cartmate.boundedContext.productService.search.app.SalesPostReindexUseCase;
import com.cartmate.cartmate.boundedContext.productService.search.out.SalesPostIndexAdmin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 처음 배포했을 때처럼 검색 별칭이 없으면 앱이 뜬 뒤 색인을 만들어 채운다. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cartmate.search.init.enabled", havingValue = "true", matchIfMissing = true)
public class SalesPostSearchIndexInitializer {

    private final SalesPostIndexAdmin indexAdmin;
    private final SalesPostReindexUseCase reindexUseCase;

    @EventListener(ApplicationReadyEvent.class)
    public void createIndexIfMissing() {
        try {
            if (indexAdmin.currentIndex().isPresent()) {
                return;
            }
            reindexUseCase.reindex();
        } catch (RuntimeException e) {
            // ES가 없어도 앱은 떠야 한다. 검색은 MySQL로 대신하고, 나중에 관리자 재색인으로 만든다
            log.error("판매글 검색 색인을 만들지 못했습니다. 검색은 MySQL로 대신합니다.", e);
        }
    }
}
