package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.post.dto.SalesPostDetailInventories;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SalesPostDetailInventories.ProductInventories;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectInventory;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectProductOption;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectProductResponse;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectSalesPostInventory;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectSalesPostOptionGroup;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectSalesPostOptionValue;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductClient;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 판매글 상세에 붙일 상품별 선택 목록 · 재고 구성을 상품에서 가져온다(SalesPostProductClient.getProduct.
 * 기본은 같은 서버 직접 호출, 서버 분리 시 GET /api/products/{postId}). 판매글 복제본에는 재고가 없다(목록 트래픽을 가볍게 두려고).
 *
 * <p>재고 구성 · 이름 · 가격 · 상품옵션은 판매글 수정 때만 바뀌어 Redis에 캐시한다(1분, ProductServiceCacheConfig). 이때 수량은 비워 둔다(null).
 * 남은 수량은 주문 · 결제 · 취소 때마다 바뀌므로 캐시하지 않고 SalesPostFacade가 상세를 볼 때마다 getStocks로 새로 붙인다.
 * 판매글 복제본을 저장할 때(등록 · 수정 · 복구) evict로 지우고, 그래도 수량의 재고 ID들과 어긋나면 SalesPostFacade가 지우고 다시 읽는다.
 *
 * <p>싱글 플라이트: 캐시가 만료 · 삭제된 순간 같은 판매글 상세가 몰리면(캐시 스탬피드) 요청마다 상품 쪽 쿼리 7번을 따로 실행한다.
 * 그래서 같은 판매글은 이 서버 안에서 한 요청만 상품을 읽고, 동시에 온 나머지는 그 결과를 같이 받는다(다른 판매글끼리는 기다리지 않는다).
 * {@code @Cacheable(sync = true)}는 RedisCache의 기본 쓰기 방식(잠금 없음)에선 막지 못하고, 잠금 방식은 캐시 전체에 잠금 하나라 쓰지 않는다.
 * 서버가 여러 대면 다시 읽기는 판매글당 서버 대수만큼 나간다.
 */
@Component
@RequiredArgsConstructor
public class SalesPostDetailInventoryLoader {

    public static final String CACHE_NAME = "salesPostDetailInventory";

    private final SalesPostProductClient salesPostApiClient;

    // 판매글 ID → 지금 상품을 읽고 있는 조회. 조회가 끝나면(성공 · 실패 모두) 지운다
    private final ConcurrentHashMap<Long, CompletableFuture<SalesPostDetailInventories>> inFlight = new ConcurrentHashMap<>();

    // 캐시에 없을 때만 실행된다(@Cacheable). 동시에 캐시를 놓친 요청들은 먼저 온 한 요청의 결과를 기다린다
    @Cacheable(cacheNames = CACHE_NAME, key = "#postId")
    public SalesPostDetailInventories load(Long postId) {
        CompletableFuture<SalesPostDetailInventories> mine = new CompletableFuture<>();
        CompletableFuture<SalesPostDetailInventories> running = inFlight.putIfAbsent(postId, mine);
        if (running != null) {
            return await(running);
        }
        try {
            SalesPostDetailInventories loaded = fetch(postId);
            mine.complete(loaded);
            return loaded;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);   // 기다리던 요청도 같은 오류로 실패한다(다음 요청은 다시 읽는다)
            throw e;
        } finally {
            inFlight.remove(postId, mine);
        }
    }

    private SalesPostDetailInventories fetch(Long postId) {
        return new SalesPostDetailInventories(salesPostApiClient.getProduct(postId).stream()
                .map(SalesPostDetailInventoryLoader::toProductInventories)
                .toList());
    }

    // 먼저 온 요청의 결과를 기다린다. 그 요청이 실패했으면 같은 예외를 던진다
    private static SalesPostDetailInventories await(CompletableFuture<SalesPostDetailInventories> running) {
        try {
            return running.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    // 판매글의 재고 구성이 바뀌었을 수 있을 때 캐시를 지운다. 다음 상세 조회가 상품에서 다시 읽는다.
    // beforeInvocation = true여야 바로 지운다(evictIfPresent). 기본값(false)은 Cache.evict라 늦게 지워질 수 있어서,
    // 지운 직후 다시 읽으면 예전 값이 나오거나, 늦게 도착한 지우기가 그 사이 새로 넣은 캐시를 지운다
    @CacheEvict(cacheNames = CACHE_NAME, key = "#postId", beforeInvocation = true)
    public void evict(Long postId) {
    }

    private static ProductInventories toProductInventories(SelectProductResponse product) {
        List<SelectInventory> inventories = product.getInventoryList() == null ? List.of() : product.getInventoryList();
        return new ProductInventories(product.getId(), optionGroups(inventories),
                inventories.stream().map(SalesPostDetailInventoryLoader::toInventory).toList());
    }

    // 재고에 쓰인 상품옵션을 옵션 종류별로 모은다. 옵션 종류는 재고 이름(예: "265 / 블랙")에 나온 순서, 상품옵션은 ID 순(중복 없음)
    private static List<SelectSalesPostOptionGroup> optionGroups(List<SelectInventory> inventories) {
        Map<Long, String> typeNames = new LinkedHashMap<>();
        Map<Long, Map<Long, String>> valuesByType = new LinkedHashMap<>();
        for (SelectInventory inventory : inventories) {
            for (SelectProductOption option : inventory.getOptionList()) {
                typeNames.putIfAbsent(option.getProductTypeId(), option.getProductTypeName());
                valuesByType.computeIfAbsent(option.getProductTypeId(), typeId -> new LinkedHashMap<>())
                        .putIfAbsent(option.getProductOptionId(), option.getProductOptionName());
            }
        }

        List<SelectSalesPostOptionGroup> groups = new ArrayList<>();
        typeNames.forEach((typeId, typeName) -> groups.add(new SelectSalesPostOptionGroup(typeId, typeName,
                valuesByType.get(typeId).entrySet().stream()
                        .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                        .map(value -> new SelectSalesPostOptionValue(value.getKey(), value.getValue()))
                        .toList())));
        return groups;
    }

    // 수량은 넣지 않는다(캐시에 남지 않게). 상세를 볼 때마다 새로 읽어 붙인다
    private static SelectSalesPostInventory toInventory(SelectInventory inventory) {
        return new SelectSalesPostInventory(
                inventory.getId(),
                inventory.getSkuName(),
                inventory.getAppliedPrice(),
                inventory.getAdditionalPrice(),
                null,
                inventory.getOptionList().stream().map(SelectProductOption::getProductOptionId).toList());
    }
}
