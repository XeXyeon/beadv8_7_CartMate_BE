package com.cartmate.cartmate.support;

import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostCreateUseCase;
import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostDeleteUseCase;
import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostPurchaseRecordUseCase;
import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostSyncProductUseCase;
import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostUpdateUseCase;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostApiClient;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostMemberClient;
import com.cartmate.cartmate.boundedContext.productService.product.app.ProductCreateUseCase;
import com.cartmate.cartmate.boundedContext.productService.product.app.ProductFacade;
import com.cartmate.cartmate.boundedContext.productService.search.out.SalesPostSearchClient;
import com.cartmate.cartmate.global.outbox.ManualOutboxRelayConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

/**
 * product-service 통합 테스트의 공통 설정. 상속한 테스트는 모두 Spring 컨텍스트 하나를 같이 쓴다.
 *
 * <p>Spring은 설정(properties · 프로필 · @Import · 목/스파이 빈 · @DynamicPropertySource)이 하나라도 다르면
 * 앱을 한 번 더 띄우고, 띄운 컨텍스트를 테스트가 끝날 때까지 들고 있다. 테스트마다 설정이 조금씩 달라
 * 컨텍스트가 25개까지 늘어 테스트 JVM 메모리가 부족했다(OutOfMemoryError, 2026-11-18).
 * 그래서 설정을 여기 한 곳에 모은다. <b>하위 클래스는 아래 항목을 따로 선언하지 않는다</b>(선언하면 컨텍스트가 다시 늘어난다).
 * <ul>
 *   <li>@SpringBootTest properties · @ActiveProfiles · @Import · @AutoConfigureMockMvc</li>
 *   <li>@MockitoBean · @MockitoSpyBean: 필요한 빈은 여기 추가하고, 하위 클래스는 필드를 그대로 쓴다</li>
 *   <li>@DynamicPropertySource</li>
 * </ul>
 *
 * <p>설정 자체를 시험하는 테스트(Tomcat 스레드 수, Redis 장애, 색인 초기화, Kafka 켬)와
 * ES 테스트({@link ProductServiceSearchTestSupport})는 이 클래스를 상속하지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "cartmate.scheduling.enabled=false",
        // ManualOutboxRelayConfig의 릴레이도 @Scheduled가 붙어 있어 저절로 돈다. 테스트가 직접 돌리도록 첫 실행을 미룬다
        "custom.outbox.relay.initial-delay=PT1H"
})
@ActiveProfiles("test")
@Import(ManualOutboxRelayConfig.class)
@AutoConfigureMockMvc
public abstract class ProductServiceTestSupport {

    // Redis 컨테이너를 먼저 띄운 뒤 웹 서버 포트를 고른다. 순서가 반대면 Docker가 그 포트를 가져가 서버가 못 뜰 수 있다
    static {
        ProductServiceRedisContainer.start();
    }

    // 바깥 요청 포트. 판매글 → 상품 API 호출(product.global.internalUrl)도 이 포트로 보낸다
    protected static final int PORT = freePort();

    @DynamicPropertySource
    static void commonProperties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("product.global.internalUrl", () -> "http://localhost:" + PORT);
        ProductServiceRedisContainer.register(registry);
    }

    // 다른 서버를 부르는 빈은 목으로 둔다. 테스트에는 회원 서버도, ES도 없다(ES 테스트는 ProductServiceSearchTestSupport)
    @MockitoBean
    protected SalesPostMemberClient salesPostMemberClient;
    @MockitoBean
    protected SalesPostSearchClient salesPostSearchClient;

    // 같은 상품 서버 안의 빈은 스파이로 둔다. 정해 두지 않은 메서드는 실제 메서드를 실행하고,
    // 실패 상황을 만들 테스트만 given/willThrow로 정한다(테스트가 끝나면 Mockito가 정한 동작을 지운다)
    // 판매글 상세의 재고 로더(SalesPostDetailInventoryLoader)는 @Cacheable 프록시라 스파이로 두지 않는다.
    // 재고 응답을 정하려면 그 아래의 salesPostApiClient.getProduct()를 정한다
    @MockitoSpyBean
    protected SalesPostApiClient salesPostApiClient;
    @MockitoSpyBean
    protected SalesPostCreateUseCase salesPostCreateUseCase;
    @MockitoSpyBean
    protected SalesPostUpdateUseCase salesPostUpdateUseCase;
    @MockitoSpyBean
    protected SalesPostDeleteUseCase salesPostDeleteUseCase;
    @MockitoSpyBean
    protected SalesPostSyncProductUseCase salesPostSyncProductUseCase;
    @MockitoSpyBean
    protected SalesPostPurchaseRecordUseCase salesPostPurchaseRecordUseCase;
    @MockitoSpyBean
    protected ProductCreateUseCase productCreateUseCase;
    @MockitoSpyBean
    protected ProductFacade productFacade;

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
