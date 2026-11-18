package com.cartmate.cartmate.support;

import com.cartmate.cartmate.boundedContext.productService.search.app.SalesPostIndexer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 실제 Elasticsearch를 쓰는 검색 테스트의 공통 설정. 상속한 테스트는 Spring 컨텍스트 하나와 ES 컨테이너 하나를 같이 쓴다.
 *
 * <p>{@link ProductServiceTestSupport}는 SalesPostSearchClient를 목으로 두므로 상속하지 않고 따로 둔다.
 * 여기서는 ES를 쓰는 빈(SalesPostSearchClient · SalesPostIndexer · SalesPostIndexAdmin)이 모두 실제 객체이고 컨테이너에 연결된다.
 * ES와 관계없는 테스트만 실행할 때는 ES 컨테이너를 띄우지 않으려고 공통 설정과 나눴다.
 *
 * <p>하위 클래스는 @SpringBootTest · @ActiveProfiles · 목/스파이 빈을 따로 선언하지 않는다(선언하면 컨텍스트가 늘어난다).
 */
@SpringBootTest(properties = "cartmate.scheduling.enabled=false")
@ActiveProfiles("test")
@ImportTestcontainers(ProductServiceElasticsearchContainer.class)
public abstract class ProductServiceSearchTestSupport {

    // 정해 두지 않으면 실제로 색인한다. 색인 실패를 만들 테스트만 willThrow로 정한다
    @MockitoSpyBean
    protected SalesPostIndexer indexer;
}
