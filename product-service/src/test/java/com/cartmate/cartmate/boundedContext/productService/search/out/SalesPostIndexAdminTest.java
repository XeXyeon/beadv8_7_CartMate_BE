package com.cartmate.cartmate.boundedContext.productService.search.out;

import com.cartmate.cartmate.support.ProductServiceSearchTestSupport;
import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SalesPostIndexAdminTest extends ProductServiceSearchTestSupport {

    @Autowired
    SalesPostIndexAdmin admin;

    @Autowired
    ElasticsearchClient client;

    private final List<String> created = new ArrayList<>();

    @AfterEach
    void 만든_색인을_지운다() {
        created.forEach(admin::deleteIndex);
    }

    @Test
    void 새_색인은_샤드1_복제본0이고_nori_분석기를_쓴다() throws IOException {
        String index = create();

        var settings = client.indices().getSettings(g -> g.index(index)).get(index).settings().index();
        var title = client.indices().getMapping(g -> g.index(index)).get(index).mappings().properties().get("title");

        assertThat(settings.numberOfShards()).isEqualTo("1");
        assertThat(settings.numberOfReplicas()).isEqualTo("0");
        assertThat(title.text().analyzer()).isEqualTo("ko_nori");
    }

    @Test
    void 별칭을_새_색인으로_옮기면_옛_색인은_더_이상_가리키지_않는다() {
        String first = create();
        String second = create();

        admin.swapAlias(first);
        admin.swapAlias(second);

        assertThat(admin.currentIndex()).contains(second);
    }

    @Test
    void 재색인_중에는_쓰기_대상이_두_개다() {
        String a = create();
        String b = create();
        admin.swapAlias(a);

        admin.markReindexing(b);
        assertThat(admin.writeTargets()).containsExactly(a, b);

        admin.swapAlias(b);
        assertThat(admin.writeTargets()).containsExactly(b);
        assertThat(admin.reindexingIndex()).isEmpty();
    }

    @Test
    void 같은_초에_만들어도_이름이_겹치지_않는다() {
        String first = create();
        String second = create();

        assertThat(second).isNotEqualTo(first);
        assertThat(List.of(first, second)).allMatch(name -> name.matches("sales-posts-\\d{14}(-\\d+)?"));
    }

    private String create() {
        String index = admin.createIndex();
        created.add(index);
        return index;
    }
}
