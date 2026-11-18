package com.cartmate.cartmate.shared.post.event;

import com.cartmate.cartmate.global.event.DomainEvent;

/**
 * 판매글에서 검색에 보이는 것(상태 · 제목 · 본문 · 상품)이 바뀌었을 때 post가 발행한다. 무엇이 바뀌었는지는 담지 않는다.
 *
 * <p>받는 쪽(product-service 검색 색인)은 판매글 id로 DB를 다시 읽어 최신 상태로 맞춘다.
 * 그래서 순서가 바뀌거나 같은 이벤트가 두 번 와도 결과가 같다. Kafka 토픽: cartmate.post.sales-post-changed
 *
 * @param postId 바뀐 판매글 ID
 */
public record SalesPostChangedEvent(Long postId) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "SalesPost";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(postId);
    }
}
