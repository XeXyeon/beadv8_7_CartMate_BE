package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateInventory;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateProductOption;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostProductImage;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SalesPostDetailInventories;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectSalesPostInventory;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SyncProduct;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectSalesPostOptionGroup;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectSalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectSalesPostRequest;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductOptionRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.product.app.TestImageFiles;
import com.cartmate.cartmate.boundedContext.productService.product.out.FakeImageStorage;
import com.cartmate.cartmate.boundedContext.productService.product.out.InventoryRepository;
import com.cartmate.cartmate.boundedContext.productService.product.out.ProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.product.out.ProductRepository;
import com.cartmate.cartmate.boundedContext.productService.code.ProductErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.support.ProductServiceTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.cartmate.cartmate.support.SeedOptions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 판매글 상세: 복제본(판매글 · 상품 · 사진)에 상품 서버의 선택 목록 · 재고를 붙인다.
 * 재고 구성(이름 · 가격 · 상품옵션)은 Redis에 캐시하고, 남은 수량은 볼 때마다 새로 읽는다.
 * post → product HTTP 호출이 실제로 일어나도록 웹 서버를 빈 포트로 띄우고, 캐시용 Redis 컨테이너를 쓴다.
 */
class SalesPostDetailFlowTest extends ProductServiceTestSupport {

    private static final Long SELLER_ID = 7L;
    private static final Long BASE_PRICE = 189000L;

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
    @Autowired
    InventoryRepository inventoryRepository;
    @Autowired
    SalesPostDetailInventoryLoader inventoryLoader;

    Long postId;
    Long productId;

    // 축구화 1개: 재고 "265 / 블랙"(추가금액 0, 3개), "255 / 화이트"(추가금액 5,000, 0개 = 품절)
    @BeforeEach
    void setUp() {
        salesPostProductImageRepository.deleteAll();
        salesPostProductOptionRepository.deleteAll();
        salesPostProductRepository.deleteAll();
        salesPostRepository.deleteAll();
        productImageRepository.deleteAll();
        productRepository.deleteAll();
        storage.reset();
        Objects.requireNonNull(cacheManager.getCache(SalesPostDetailInventoryLoader.CACHE_NAME)).clear();

        CreateSalesPostProduct shoes = new CreateSalesPostProduct(null, null, 3L, "머큐리얼 FG", "천연잔디용", BASE_PRICE,
                List.of(new CreateSalesPostProductImage(null, null, null, 0, null)),
                List.of(
                        new CreateInventory(List.of(
                                new CreateProductOption(SHOE_SIZE, SHOE_265), new CreateProductOption(COLOR, BLACK)), 0L, 3),
                        new CreateInventory(List.of(
                                new CreateProductOption(SHOE_SIZE, SHOE_255), new CreateProductOption(COLOR, WHITE)), 5000L, 0)));
        salesPostFacade.registerSalesPost(
                new CreateSalesPostRequest(null, SELLER_ID, "축구화 팝니다", "새 상품", List.of(shoes)),
                List.of(TestImageFiles.jpeg()), 100L);

        postId = salesPostRepository.findAll().getFirst().getId();
        productId = productRepository.findAll().getFirst().getId();
        reset(salesPostApiClient);
    }

    @Test
    @DisplayName("상세: 상품마다 옵션 종류별 선택 목록(중복 없이)과 재고(이름 · 최종 가격 · 구매 가능 수량 · 상품옵션)가 붙는다")
    void detailHasOptionGroupsAndInventories() {
        SelectSalesPostProduct product = detailProduct();

        // 옵션 종류는 재고 이름 순서(사이즈 → 색상), 상품옵션은 ID 순
        assertThat(product.getOptionGroups())
                .extracting(SelectSalesPostOptionGroup::typeId, SelectSalesPostOptionGroup::typeName)
                .containsExactly(tuple(SHOE_SIZE, "신발 사이즈"), tuple(COLOR, "색상"));
        assertThat(product.getOptionGroups().get(0).options())
                .extracting(option -> option.optionId(), option -> option.name())
                .containsExactly(tuple(SHOE_255, "255"), tuple(SHOE_265, "265"));
        assertThat(product.getOptionGroups().get(1).options())
                .extracting(option -> option.optionId(), option -> option.name())
                .containsExactly(tuple(BLACK, "블랙"), tuple(WHITE, "화이트"));

        assertThat(product.getInventoryList())
                .extracting(SelectSalesPostInventory::skuName, SelectSalesPostInventory::appliedPrice,
                        SelectSalesPostInventory::additionalPrice, SelectSalesPostInventory::stockQuantity,
                        SelectSalesPostInventory::optionIds)
                .containsExactlyInAnyOrder(
                        tuple("265 / 블랙", 189000L, 0L, 3, List.of(SHOE_265, BLACK)),
                        tuple("255 / 화이트", 194000L, 5000L, 0, List.of(SHOE_255, WHITE)));
    }

    @Test
    @DisplayName("목록: 선택 목록 · 재고를 붙이지 않고 상품 서버도 부르지 않는다")
    void listHasNoInventories() {
        List<SelectSalesPostProduct> products = salesPostFacade
                .selectSalesPostAll(new SelectSalesPostRequest(), PageRequest.of(0, 10))
                .getData().content().getFirst().getProductList();

        assertThat(products.getFirst().getOptionGroups()).isEmpty();
        assertThat(products.getFirst().getInventoryList()).isEmpty();
        verify(salesPostApiClient, times(0)).getProduct(anyLong());
    }

    @Test
    @DisplayName("캐시: 재고 구성은 한 번만 상품에서 가져와 수량 없이 캐시하고, 남은 수량은 볼 때마다 새로 읽는다")
    void inventoryLayoutIsCachedButStocksAreReadEveryTime() {
        detailProduct();
        SalesPostDetailInventories cached = awaitCached();
        detailProduct();

        verify(salesPostApiClient, times(1)).getProduct(postId);
        verify(salesPostApiClient, times(2)).getStocks(postId);
        assertThat(cached.products().getFirst().inventories())
                .extracting(SelectSalesPostInventory::stockQuantity)
                .containsOnlyNulls();   // 수량은 캐시에 남지 않는다
    }

    @Test
    @DisplayName("남은 수량: 선점하면 캐시가 있어도 다음 상세에 바로 줄어든 수량이 보인다")
    void stockChangeIsVisibleImmediately() {
        assertThat(stockOf("265 / 블랙")).isEqualTo(3);
        awaitCached();

        inventoryRepository.reserve(inventoryIdOf("265 / 블랙"), 2);

        assertThat(stockOf("265 / 블랙")).isEqualTo(1);
        verify(salesPostApiClient, times(1)).getProduct(postId);   // 재고 구성은 캐시에서
    }

    @Test
    @DisplayName("예전 캐시: 캐시의 재고 구성이 지금 판매 중인 재고와 다르면 지우고 상품에서 다시 읽는다")
    void staleLayoutIsReloaded() {
        inventoryCache().put(postId, new SalesPostDetailInventories(List.of()));   // 재고가 없던 때의 캐시
        assertThat(awaitCached().products()).isEmpty();

        SelectSalesPostProduct product = detailProduct();

        assertThat(product.getInventoryList()).hasSize(2);
        verify(salesPostApiClient, times(1)).getProduct(postId);
    }

    @Test
    @DisplayName("복제본 저장(등록 · 수정 · 복구): 재고 구성 캐시를 지워 다음 상세가 바뀐 값을 읽는다")
    void syncProductEvictsLayoutCache() {
        detailProduct();
        awaitCached();

        salesPostSyncProductUseCase.syncProduct(List.of(new SyncProduct(productId, SELLER_ID, postId, 3L,
                "머큐리얼 FG", "천연잔디용", BASE_PRICE, LocalDateTime.now(), LocalDateTime.now(),
                List.of(), List.of(), List.of())));

        assertThat(inventoryCache().get(postId)).isNull();
    }

    @Test
    @DisplayName("싱글 플라이트: 캐시가 없을 때 같은 판매글 상세가 동시에 몰려도 상품 재고 구성은 한 번만 읽는다")
    void concurrentMissesLoadOnce() throws Exception {
        willAnswer(invocation -> {
            Thread.sleep(300);   // 상품 조회가 끝나기 전에 나머지 요청이 모두 캐시를 놓치게
            return invocation.callRealMethod();
        }).given(salesPostApiClient).getProduct(postId);

        List<SelectSalesPostProduct> results = runConcurrently(8, this::detailProduct);

        verify(salesPostApiClient, times(1)).getProduct(postId);
        assertThat(results).allSatisfy(product -> assertThat(product.getInventoryList()).hasSize(2));
    }

    @Test
    @DisplayName("싱글 플라이트: 먼저 읽던 요청이 실패하면 기다리던 요청도 실패하고, 다음 요청은 다시 읽는다")
    void failedLoadIsSharedThenRetried() throws Exception {
        willAnswer(invocation -> {
            Thread.sleep(300);
            throw new ServiceException(ProductErrorCode.PRODUCT_SELECT_FAILED);
        }).given(salesPostApiClient).getProduct(postId);

        List<Throwable> errors = runConcurrently(4, () -> {
            try {
                inventoryLoader.load(postId);
                return null;
            } catch (ServiceException e) {
                return e;
            }
        });

        assertThat(errors).hasSize(4).allSatisfy(error -> assertThat(error).isInstanceOf(ServiceException.class));
        verify(salesPostApiClient, times(1)).getProduct(postId);

        reset(salesPostApiClient);   // 상품이 회복됨
        assertThat(detailProduct().getInventoryList()).hasSize(2);
        verify(salesPostApiClient, times(1)).getProduct(postId);
    }

    // 같은 순간에 task를 count번 실행하고 결과를 모은다
    private <T> List<T> runConcurrently(int count, java.util.concurrent.Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new java.util.ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private Cache inventoryCache() {
        return Objects.requireNonNull(cacheManager.getCache(SalesPostDetailInventoryLoader.CACHE_NAME));
    }

    // Lettuce를 쓰면 RedisCache 저장(put)이 비동기라, 상세 직후엔 아직 없을 수 있다. 최대 3초 기다린다
    private SalesPostDetailInventories awaitCached() {
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            Cache.ValueWrapper cached = inventoryCache().get(postId);
            if (cached != null) {
                return (SalesPostDetailInventories) cached.get();
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("재고 구성 캐시가 3초 안에 저장되지 않았습니다");
    }

    private int stockOf(String skuName) {
        return detailProduct().getInventoryList().stream()
                .filter(inventory -> inventory.skuName().equals(skuName))
                .findFirst()
                .orElseThrow()
                .stockQuantity();
    }

    private Long inventoryIdOf(String skuName) {
        return inventoryRepository.findAll().stream()
                .filter(inventory -> inventory.getSkuName().equals(skuName))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private SelectSalesPostProduct detailProduct() {
        return salesPostFacade.selectSalesPostDetail(postId, null).getData().getProductList().stream()
                .filter(product -> product.getId().equals(productId))
                .findFirst()
                .orElseThrow();
    }

}
