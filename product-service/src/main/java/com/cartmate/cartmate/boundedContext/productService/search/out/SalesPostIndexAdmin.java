package com.cartmate.cartmate.boundedContext.productService.search.out;

import static com.cartmate.cartmate.boundedContext.productService.search.out.SalesPostIndexSchema.ALIAS;
import static com.cartmate.cartmate.boundedContext.productService.search.out.SalesPostIndexSchema.REINDEXING_ALIAS;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 판매글 검색 색인을 만들고 지우며, 별칭이 어느 색인을 가리키는지 관리한다. */
@Component
@RequiredArgsConstructor
public class SalesPostIndexAdmin {

    private final ElasticsearchClient client;

    /** sales-posts-<서울 시각 초>. 같은 초에 이미 있으면 뒤에 -2, -3 … 을 붙인다. */
    public String newIndexName() {
        String base = SalesPostIndexSchema.newIndexName();
        String name = base;
        for (int n = 2; exists(name); n++) {
            name = base + "-" + n;
        }
        return name;
    }

    private boolean exists(String index) {
        try {
            return client.indices().exists(e -> e.index(index)).value();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String createIndex() {
        String index = newIndexName();
        createIndex(index);
        return index;
    }

    /** 이름을 먼저 정해 두고 만든다. 만드는 중에 타임아웃이 나도 부른 쪽이 그 이름으로 지울 수 있다. */
    public void createIndex(String index) {
        try (InputStream json = SalesPostIndexSchema.settingsAndMappings()) {
            client.indices().create(c -> c.index(index).withJson(json));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 지난 재색인이 타임아웃 등으로 남긴 것을 지운다. 검색 별칭이 가리키는 색인 말고 판매글 검색 색인은 모두 지운다
     * (지우면 붙어 있던 재색인 별칭도 함께 사라진다). 재색인 락 안에서만 부른다.
     */
    public void cleanUpLeftovers() {
        Optional<String> current = currentIndex();
        allIndices().stream()
                .filter(index -> current.filter(index::equals).isEmpty())
                .forEach(this::deleteIndex);
    }

    private Set<String> allIndices() {
        try {
            return client.indices().get(g -> g.index(ALIAS + "-*")).indices().keySet();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<String> currentIndex() {
        return indexOf(ALIAS);
    }

    public Optional<String> reindexingIndex() {
        return indexOf(REINDEXING_ALIAS);
    }

    public void markReindexing(String index) {
        updateAliases(List.of(Action.of(a -> a.add(add -> add.index(index).alias(REINDEXING_ALIAS)))));
    }

    /** 검색 별칭을 새 색인으로 옮기고 재색인 별칭을 지운다. 한 요청이라 중간에 별칭이 비는 순간이 없다. */
    public void swapAlias(String newIndex) {
        List<Action> actions = new ArrayList<>();
        currentIndex().filter(old -> !old.equals(newIndex))
                .ifPresent(old -> actions.add(Action.of(a -> a.remove(r -> r.index(old).alias(ALIAS)))));
        reindexingIndex()
                .ifPresent(idx -> actions.add(Action.of(a -> a.remove(r -> r.index(idx).alias(REINDEXING_ALIAS)))));
        actions.add(Action.of(a -> a.add(add -> add.index(newIndex).alias(ALIAS))));
        updateAliases(actions);
    }

    public void deleteIndex(String index) {
        try {
            client.indices().delete(d -> d.index(index));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 문서를 써야 할 색인들. 재색인 중이면 옛 색인과 새 색인 둘 다다. */
    public List<String> writeTargets() {
        List<String> targets = new ArrayList<>();
        currentIndex().ifPresent(targets::add);
        reindexingIndex().filter(idx -> !targets.contains(idx)).ifPresent(targets::add);
        return targets;
    }

    /** ES가 응답하면 true. 복구 스케줄러가 재색인을 시도할지 정할 때 쓴다. */
    public boolean isAvailable() {
        try {
            return client.ping().value();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private Optional<String> indexOf(String alias) {
        try {
            if (!client.indices().existsAlias(e -> e.name(alias)).value()) {
                return Optional.empty();
            }
            return client.indices().getAlias(g -> g.name(alias)).aliases().keySet().stream().findFirst();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void updateAliases(List<Action> actions) {
        try {
            client.indices().updateAliases(u -> u.actions(actions));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
