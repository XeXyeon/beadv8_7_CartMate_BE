package com.cartmate.cartmate.boundedContext.productService.config;

import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostDetailInventoryLoader;
import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostFacade;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateInventory;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateProductOption;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostProductImage;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostRequest;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductOptionRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.product.app.TestImageFiles;
import com.cartmate.cartmate.boundedContext.productService.product.out.FakeImageStorage;
import com.cartmate.cartmate.boundedContext.productService.product.out.ProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.product.out.ProductRepository;
import com.cartmate.cartmate.support.ProductServiceRedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.cartmate.cartmate.support.SeedOptions.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판매글 상세 교착상태 재현.
 *
 * 판매글 상세는 같은 서버의 상품 API를 HTTP로 부르고 응답을 기다린다(SalesPostApiClient). 내부 요청이 바깥 요청과 같은
 * Tomcat 스레드 풀을 쓰면, 판매글 상세 요청이 스레드를 모두 쓰고 각자 내부 요청을 기다리다 교착상태가 된다.
 * 바깥 포트 스레드를 2개로 줄이고 서로 다른 판매글 4개의 상세를 동시에 요청해, 내부 요청이 전용 포트에서 처리되어
 * 모두 성공하는지 본다. (판매글이 다르면 재고 2초 캐시에 걸리지 않아 4개 모두 내부 요청을 보낸다)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "cartmate.scheduling.enabled=false",
                "server.tomcat.threads.max=2",
                "server.tomcat.threads.min-spare=1"
        })
@ActiveProfiles("test")
class ProductInternalServerDeadlockTest {

    private static final int MAIN_PORT;
    private static final int INTERNAL_PORT;
    private static final int POSTS = 4;

    static {
        ProductServiceRedisContainer.start();   // Redis 컨테이너를 먼저 띄운 뒤 빈 포트를 고른다(포트 충돌 방지)
        MAIN_PORT = freePort();
        INTERNAL_PORT = freePort();
    }

    @DynamicPropertySource
    static void ports(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> MAIN_PORT);
        registry.add("product.internal-server.port", () -> INTERNAL_PORT);
        registry.add("product.global.internalUrl", () -> "http://localhost:" + INTERNAL_PORT);
        ProductServiceRedisContainer.register(registry);
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Autowired
    SalesPostFacade salesPostFacade;
    @Autowired
    CacheManager cacheManager;
    @Autowired
    SalesPostRepository salesPostRepository;
    @Autowired
    SalesPostProductRepository salesPostProductRepository;
    @Autowired
    SalesPostProductImageRepository salesPostProductImageRepository;
    @Autowired
    SalesPostProductOptionRepository salesPostProductOptionRepository;
    @Autowired
    ProductRepository productRepository;
    @Autowired
    ProductImageRepository productImageRepository;
    @Autowired
    FakeImageStorage storage;

    List<Long> postIds;

    @BeforeEach
    void setUp() {
        salesPostProductImageRepository.deleteAll();
        salesPostProductOptionRepository.deleteAll();
        salesPostProductRepository.deleteAll();
        salesPostRepository.deleteAll();
        productImageRepository.deleteAll();
        productRepository.deleteAll();
        storage.reset();

        // 판매글 등록도 판매글 → 상품 HTTP 호출(내부 포트)을 거친다
        IntStream.range(0, POSTS).forEach(i -> {
            CreateSalesPostProduct shoes = new CreateSalesPostProduct(null, null, 3L, "머큐리얼 FG " + i, "천연잔디용", 189000L,
                    List.of(new CreateSalesPostProductImage(null, null, null, 0, null)),
                    List.of(new CreateInventory(List.of(
                            new CreateProductOption(SHOE_SIZE, SHOE_265), new CreateProductOption(COLOR, BLACK)), 0L, 3)));
            salesPostFacade.registerSalesPost(
                    new CreateSalesPostRequest(null, 7L, "축구화 팝니다 " + i, "새 상품", List.of(shoes)),
                    List.of(TestImageFiles.jpeg()), 100L);
        });
        postIds = salesPostRepository.findAll().stream().map(post -> post.getId()).toList();
        Objects.requireNonNull(cacheManager.getCache(SalesPostDetailInventoryLoader.CACHE_NAME)).clear();
    }

    @Test
    @DisplayName("바깥 포트 스레드(2개)보다 많은 판매글 상세가 동시에 와도, 내부 요청은 전용 포트에서 처리되어 모두 성공한다")
    void concurrentDetails_moreThanMainThreads_allSucceed() {
        List<CompletableFuture<HttpResponse<String>>> responses = postIds.stream()
                .map(postId -> http.sendAsync(get(MAIN_PORT, "/api/sales-posts/detail/" + postId), HttpResponse.BodyHandlers.ofString()))
                .toList();

        // 교착상태면 내부 요청 응답 대기(30초)까지 멈춘다. 10초 안에 모두 200이어야 한다
        List<Integer> statuses = responses.stream()
                .map(future -> future.orTimeout(10, TimeUnit.SECONDS).join().statusCode())
                .toList();
        assertThat(statuses).hasSize(POSTS).containsOnly(200);
    }

    @Test
    @DisplayName("내부 포트: 상품 API는 처리하고, 그 밖의 경로는 404 (../ 우회 포함)")
    void internalPort_onlyProductApi() throws Exception {
        Long postId = postIds.getFirst();

        assertThat(send(INTERNAL_PORT, "/api/products/" + postId).statusCode()).isEqualTo(200);
        assertThat(send(INTERNAL_PORT, "/api/sales-posts/detail/" + postId).statusCode()).isEqualTo(404);
        assertThat(send(INTERNAL_PORT, "/api/products/../sales-posts/detail/" + postId).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("바깥 포트: 상품 API도 그대로 처리한다")
    void mainPort_productApiStillServed() throws Exception {
        assertThat(send(MAIN_PORT, "/api/products/" + postIds.getFirst()).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> send(int port, String path) throws Exception {
        return http.send(get(port, path), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest get(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10)).GET().build();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
