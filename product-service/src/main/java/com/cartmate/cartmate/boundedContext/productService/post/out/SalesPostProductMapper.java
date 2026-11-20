package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSnapshot;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CompleteProductUpdateRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateInventory;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateProduct;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateProductImage;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateProductOption;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateProductRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.RestoreInventory;
import com.cartmate.cartmate.boundedContext.productService.post.dto.RestoreProduct;
import com.cartmate.cartmate.boundedContext.productService.post.dto.RestoreProductImage;
import com.cartmate.cartmate.boundedContext.productService.post.dto.RestoreProductsRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectInventory;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectProductImage;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectProductOption;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectProductResponse;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SyncProduct;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SyncProductImage;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SyncProductOption;
import com.cartmate.cartmate.boundedContext.productService.post.dto.UpdateSalesPostInventory;
import com.cartmate.cartmate.boundedContext.productService.post.dto.UpdateSalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.dto.UpdateSalesPostProductImage;
import com.cartmate.cartmate.boundedContext.productService.post.dto.UpdateSalesPostRequest;
import com.cartmate.cartmate.boundedContext.productService.product.domain.ProductStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 판매글 DTO ↔ 상품 DTO 변환.
 *
 * <p>판매글과 상품은 같은 모양의 DTO를 따로 가진다(서버 분리 대비). HTTP 호출(SalesPostApiClient)에서는 JSON이 이 변환을
 * 대신했고, 직접 호출(LocalSalesPostProductClient)에서는 여기서 필드를 하나씩 옮겨 담는다.
 * 한쪽 DTO에 필드를 추가하면 여기도 같이 고친다(HTTP일 때 JSON으로 오가던 필드와 같게).
 *
 * <p>스냅샷 → 판매글 요청 DTO 변환(toRestoreRequest, toCompleteRequest)은 두 구현이 같이 쓴다.
 *
 * <p>서버 분리 시: 직접 호출 구현을 지우면 상품 DTO로 가는 변환(toProduct*, fromProduct*)도 같이 지운다.
 */
final class SalesPostProductMapper {

    private SalesPostProductMapper() {
    }

    // ===== 스냅샷 → 판매글 요청 DTO (HTTP · 직접 호출 공통) =====

    static CompleteProductUpdateRequest toCompleteRequest(SalesPostSnapshot snapshot) {
        return new CompleteProductUpdateRequest(snapshot.products().stream()
                .map(p -> new CompleteProductUpdateRequest.Item(p.id(), p.status()))
                .toList());
    }

    static RestoreProductsRequest toRestoreRequest(Long postId, SalesPostSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalStateException("복구할 스냅샷이 없습니다. postId=" + postId);
        }
        List<RestoreProduct> productList = snapshot.products().stream()
                .map(p -> new RestoreProduct(
                        p.id(),
                        p.categoryId(),
                        p.name(),
                        p.description(),
                        p.basePrice(),
                        p.images().stream()
                                .map(i -> new RestoreProductImage(i.imageId(), i.sortOrder()))
                                .toList(),
                        toRestoreInventories(p.inventories()),
                        p.status()   // 수정 전 판매 상태로 되돌린다(수정 중 UPDATING 해제)
                ))
                .toList();
        return new RestoreProductsRequest(productList);
    }

    // 재고가 없는 스냅샷(재고 도입 전에 저장된 것)은 null로 보내 상품 쪽이 재고를 건드리지 않게 한다
    private static List<RestoreInventory> toRestoreInventories(List<SalesPostSnapshot.InventorySnapshot> inventories) {
        if (inventories == null || inventories.isEmpty()) {
            return null;
        }
        return inventories.stream()
                .map(i -> new RestoreInventory(i.inventoryId(), i.additionalPrice(), i.stockQuantity()))
                .toList();
    }

    // ===== 판매글 → 상품 =====

    static ProductStatus toProductStatus(SalesPostProductStatus status) {
        return status == null ? null : ProductStatus.valueOf(status.name());
    }

    static com.cartmate.cartmate.boundedContext.productService.product.dto.CreateProductRequest toProductCreate(
            CreateProductRequest request) {
        return new com.cartmate.cartmate.boundedContext.productService.product.dto.CreateProductRequest(
                request.getPostId(),
                map(request.getProductList(), SalesPostProductMapper::toProductCreate));
    }

    private static com.cartmate.cartmate.boundedContext.productService.product.dto.CreateProduct toProductCreate(
            CreateProduct p) {
        return new com.cartmate.cartmate.boundedContext.productService.product.dto.CreateProduct(
                p.getId(),
                p.getSellerId(),
                p.getSellerMemberId(),
                p.getSalesPostId(),
                p.getCategoryId(),
                p.getName(),
                p.getDescription(),
                p.getBasePrice(),
                map(p.getProductImageList(), SalesPostProductMapper::toProductCreateImage),
                map(p.getInventoryList(), SalesPostProductMapper::toProductCreateInventory));
    }

    // 파일(file)은 HTTP에서도 JSON에 담지 않고(@JsonIgnore) 상품 쪽이 images로 다시 채운다. 여기서도 옮기지 않는다
    private static com.cartmate.cartmate.boundedContext.productService.product.dto.CreateProductImage toProductCreateImage(
            CreateProductImage i) {
        return new com.cartmate.cartmate.boundedContext.productService.product.dto.CreateProductImage(
                i.getId(), i.getSellerId(), i.getProductId(), i.getS3Key(), i.getImageSeq(), null);
    }

    private static com.cartmate.cartmate.boundedContext.productService.product.dto.CreateInventory toProductCreateInventory(
            CreateInventory i) {
        return new com.cartmate.cartmate.boundedContext.productService.product.dto.CreateInventory(
                toProductOptions(i.getOptionList()), i.getAdditionalPrice(), i.getStockQuantity());
    }

    private static List<com.cartmate.cartmate.boundedContext.productService.product.dto.CreateProductOption> toProductOptions(
            List<CreateProductOption> options) {
        return map(options, o -> new com.cartmate.cartmate.boundedContext.productService.product.dto.CreateProductOption(
                o.getProductTypeId(), o.getProductOptionId()));
    }

    // 판매글 수정 요청에서 상품 쪽이 쓰는 부분(id, 상품 목록)만 옮긴다. HTTP에서도 title · content · sellerId는 상품 쪽이 읽지 않았다
    static com.cartmate.cartmate.boundedContext.productService.product.dto.UpdateProductsRequest toProductUpdate(
            UpdateSalesPostRequest request) {
        return new com.cartmate.cartmate.boundedContext.productService.product.dto.UpdateProductsRequest(
                request.getId(),
                map(request.getProductList(), SalesPostProductMapper::toProductUpdate));
    }

    private static com.cartmate.cartmate.boundedContext.productService.product.dto.UpdateProduct toProductUpdate(
            UpdateSalesPostProduct p) {
        return new com.cartmate.cartmate.boundedContext.productService.product.dto.UpdateProduct(
                p.getId(),
                p.getCategoryId(),
                p.getName(),
                p.getDescription(),
                p.getBasePrice(),
                map(p.getProductImageList(), SalesPostProductMapper::toProductUpdateImage),
                map(p.getInventoryList(), SalesPostProductMapper::toProductUpdateInventory));
    }

    private static com.cartmate.cartmate.boundedContext.productService.product.dto.UpdateProductImage toProductUpdateImage(
            UpdateSalesPostProductImage i) {
        return new com.cartmate.cartmate.boundedContext.productService.product.dto.UpdateProductImage(
                i.getId(), i.getProductId(), i.getS3Key(), i.getImageSeq(), null);
    }

    private static com.cartmate.cartmate.boundedContext.productService.product.dto.UpdateInventory toProductUpdateInventory(
            UpdateSalesPostInventory i) {
        return new com.cartmate.cartmate.boundedContext.productService.product.dto.UpdateInventory(
                i.getId(), toProductOptions(i.getOptionList()), i.getAdditionalPrice(), i.getStockQuantity());
    }

    static com.cartmate.cartmate.boundedContext.productService.product.dto.CompleteProductUpdateRequest toProductComplete(
            CompleteProductUpdateRequest request) {
        return new com.cartmate.cartmate.boundedContext.productService.product.dto.CompleteProductUpdateRequest(
                map(request.getProductList(), item ->
                        new com.cartmate.cartmate.boundedContext.productService.product.dto.CompleteProductUpdateRequest.Item(
                                item.getId(), toProductStatus(item.getStatus()))));
    }

    static com.cartmate.cartmate.boundedContext.productService.product.dto.RestoreProductsRequest toProductRestore(
            RestoreProductsRequest request) {
        return new com.cartmate.cartmate.boundedContext.productService.product.dto.RestoreProductsRequest(
                map(request.getProductList(), p ->
                        new com.cartmate.cartmate.boundedContext.productService.product.dto.RestoreProduct(
                                p.getId(),
                                p.getCategoryId(),
                                p.getName(),
                                p.getDescription(),
                                p.getBasePrice(),
                                map(p.getProductImageList(), i ->
                                        new com.cartmate.cartmate.boundedContext.productService.product.dto.RestoreProductImage(
                                                i.getId(), i.getImageSeq())),
                                map(p.getInventoryList(), i ->
                                        new com.cartmate.cartmate.boundedContext.productService.product.dto.RestoreInventory(
                                                i.getId(), i.getAdditionalPrice(), i.getStockQuantity())),
                                toProductStatus(p.getStatus()))));
    }

    // ===== 상품 → 판매글 =====

    static SalesPostProductStatus toPostStatus(ProductStatus status) {
        return status == null ? null : SalesPostProductStatus.valueOf(status.name());
    }

    static List<SyncProduct> fromProductSync(
            List<com.cartmate.cartmate.boundedContext.productService.product.dto.SyncProduct> products) {
        List<SyncProduct> result = map(products, SalesPostProductMapper::fromProductSync);
        return result == null ? List.of() : result;
    }

    private static SyncProduct fromProductSync(com.cartmate.cartmate.boundedContext.productService.product.dto.SyncProduct p) {
        return new SyncProduct(
                p.getId(),
                p.getSellerId(),
                p.getSalesPostId(),
                p.getCategoryId(),
                p.getName(),
                p.getDescription(),
                p.getBasePrice(),
                p.getCreateDate(),
                p.getModifyDate(),
                map(p.getProductImageList(), i -> new SyncProductImage(
                        i.getId(), i.getSellerId(), i.getS3Key(), i.getImageSeq(),
                        toPostStatus(i.getStatus()), i.getCreateDate(), i.getModifyDate())),
                map(p.getProductOptionList(), o -> new SyncProductOption(
                        o.getId(), o.getProductTypeId(), o.getProductOptionId(),
                        o.getCreateDate(), o.getModifyDate(), toPostStatus(o.getStatus()))),
                // HTTP에서는 필드 기본값(빈 목록)이 쓰였다. null이면 같은 결과가 되게 빈 목록으로 둔다
                p.getRemovedInventoryIds() == null ? new ArrayList<>() : new ArrayList<>(p.getRemovedInventoryIds()));
    }

    static List<SelectProductResponse> fromProductSelect(
            List<com.cartmate.cartmate.boundedContext.productService.product.dto.SelectProductResponse> products) {
        List<SelectProductResponse> result = map(products, SalesPostProductMapper::fromProductSelect);
        return result == null ? List.of() : result;
    }

    private static SelectProductResponse fromProductSelect(
            com.cartmate.cartmate.boundedContext.productService.product.dto.SelectProductResponse p) {
        return new SelectProductResponse(
                p.getId(),
                p.getSalesPostId(),
                p.getCategoryId(),
                p.getName(),
                p.getDescription(),
                p.getBasePrice(),
                toPostStatus(p.getStatus()),
                map(p.getProductImageList(), i -> new SelectProductImage(
                        i.getId(), i.getS3key(), i.getImageSeq(), toPostStatus(i.getStatus()),
                        i.getCreatedAt(), i.getUpdatedAt())),
                map(p.getProductOptionList(), SalesPostProductMapper::fromProductOption),
                map(p.getInventoryList(), i -> new SelectInventory(
                        i.getId(), i.getSkuName(), i.getAppliedPrice(), i.getAdditionalPrice(),
                        i.getStockQuantity(), i.getReservedQuantity(),
                        map(i.getOptionList(), SalesPostProductMapper::fromProductOption))));
    }

    private static SelectProductOption fromProductOption(
            com.cartmate.cartmate.boundedContext.productService.product.dto.SelectProductOption o) {
        return new SelectProductOption(
                o.getId(), o.getProductTypeId(), o.getProductOptionId(),
                o.getCreatedAt(), o.getUpdatedAt(), o.getProductTypeName(), o.getProductOptionName());
    }

    // null 목록은 null로 둔다(HTTP JSON에서도 null은 null로 왔다).
    // 받는 쪽이 목록을 고칠 수 있게 HTTP(JSON → ArrayList)와 같은 수정 가능한 목록으로 만든다
    private static <A, B> List<B> map(List<A> list, Function<A, B> mapper) {
        return list == null ? null : list.stream().map(mapper).collect(Collectors.toCollection(ArrayList::new));
    }
}
