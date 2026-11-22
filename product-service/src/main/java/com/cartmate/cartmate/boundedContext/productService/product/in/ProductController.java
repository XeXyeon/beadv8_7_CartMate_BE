package com.cartmate.cartmate.boundedContext.productService.product.in;

import com.cartmate.cartmate.boundedContext.productService.product.dto.CategoryResponse;
import com.cartmate.cartmate.boundedContext.productService.product.dto.InventoryStock;
import com.cartmate.cartmate.boundedContext.productService.product.dto.CompleteProductUpdateRequest;
import com.cartmate.cartmate.boundedContext.productService.product.dto.ProductOptionTypeResponse;
import com.cartmate.cartmate.boundedContext.productService.product.dto.SelectProductResponse;
import com.cartmate.cartmate.boundedContext.productService.product.app.ProductFacade;
import com.cartmate.cartmate.global.rsData.RsData;
import com.cartmate.cartmate.boundedContext.productService.product.dto.CreateProductRequest;
import com.cartmate.cartmate.boundedContext.productService.product.dto.RestoreProductsRequest;
import com.cartmate.cartmate.boundedContext.productService.product.dto.SyncProduct;
import com.cartmate.cartmate.boundedContext.productService.product.dto.UpdateProductsRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.cartmate.cartmate.boundedContext.productService.product.dto.ProductStatusChangeRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 상품 API.
 *
 * <p>등록(POST) · 수정(PATCH) · 복구(/restore) · 수정 끝(/update-complete) · 상태 변경(/status)은 판매글만 부르는 API다.
 * 기본(product.client=local)에서는 판매글이 ProductFacade를 직접 불러 이 API들을 쓰지 않는다.
 * product.client=http(서버 분리 시)일 때 SalesPostApiClient가 부른다.
 *
 * <p>서버 분리 시: 이 API들은 지금 로그인 없이 열려 있다(/api/products/**). 분리해서 판매글이 HTTP로 부르기 전에
 * 내부 호출 키(/internal/** 방식) 등으로 외부에서 부르지 못하게 막아야 한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/products")
public class ProductController {
    private final ProductFacade productFacade;

    // 저장한 상품을 응답으로 돌려준다. post가 이걸로 복제본을 만든다(복제본 저장 실패 = 등록 실패 → post가 보상)
    @PostMapping
    public ResponseEntity<RsData<List<SyncProduct>>> registerProducts(@RequestPart("request") CreateProductRequest request,
                                                                      @RequestPart(value = "images") List<MultipartFile> images) {
        List<SyncProduct> saved = productFacade.registerProduct(request, images);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RsData<>("201-1", "상품 등록에 성공했습니다.", saved));
    }

    @DeleteMapping("/sellers/{sellerId}/products/{productId}")
    public ResponseEntity<Void> deleteProducts(
            @PathVariable Long sellerId,
            @PathVariable Long productId) {
        productFacade.deleteProductByUser(productId, sellerId);
        return ResponseEntity.status(HttpStatus.OK).build();
    }

    // 수정한 상품을 응답으로 돌려준다. post가 이걸로 복제본을 갱신한다(복제본 갱신 실패 = 수정 실패 → post가 복구)
    @PatchMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RsData<List<SyncProduct>>> updateProducts(
            @RequestPart("request") UpdateProductsRequest request,
            @RequestPart(value = "images", required = false) List<MultipartFile> images
    ) {
        List<SyncProduct> updated = productFacade.updateProducts(request, images);
        return ResponseEntity.ok(new RsData<>("200-1", "상품 수정에 성공했습니다.", updated));
    }

    // 되돌린 상품을 응답으로 돌려준다. post가 이걸로 복제본도 되돌린다
    @PutMapping("/{postId}/restore")
    public ResponseEntity<RsData<List<SyncProduct>>> restoreProducts(
            @PathVariable Long postId,
            @RequestBody RestoreProductsRequest request
    ) {
        List<SyncProduct> restored = productFacade.restoreProducts(postId, request);
        return ResponseEntity.ok(new RsData<>("200-1", "상품 복구에 성공했습니다.", restored));
    }

    // 판매글 수정 끝: 수정 중(UPDATING)인 상품을 수정 전 상태로 되돌린다. post가 수정 완료 처리 직전에 부른다
    @PutMapping("/{postId}/update-complete")
    @Operation(operationId = "completeProductUpdate", summary = "판매글 수정 끝 - 상품을 수정 전 판매 상태로 (내부 호출)")
    public ResponseEntity<Void> completeProductUpdate(
            @PathVariable Long postId,
            @RequestBody CompleteProductUpdateRequest request
    ) {
        productFacade.completeProductUpdate(postId, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{postId}")
    public ResponseEntity<RsData<List<SelectProductResponse>>> selectProductsByPost(@PathVariable Long postId) {
        return ResponseEntity.ok(productFacade.selectProductsByPost(postId));
    }

    // 판매글의 재고 수량만 (판매글 상세의 남은 수량. 서버 분리 시 판매글이 HTTP로 부른다)
    @GetMapping("/{postId}/stocks")
    @Operation(operationId = "selectStocksByPost", summary = "판매글의 재고 수량 (내부 호출)")
    public ResponseEntity<RsData<List<InventoryStock>>> selectStocksByPost(@PathVariable Long postId) {
        return ResponseEntity.ok(productFacade.selectStocksByPost(postId));
    }

    // 카테고리 전체 (상품 등록 화면의 카테고리 선택 목록). 고정 경로라 /{postId}보다 먼저 매칭된다
    @GetMapping("/categories")
    @Operation(operationId = "getProductCategories", summary = "카테고리 전체")
    public ResponseEntity<RsData<List<CategoryResponse>>> getProductCategories() {
        return ResponseEntity.ok(new RsData<>("200-1", "카테고리 목록 조회에 성공했습니다.",
                productFacade.findCategories()));
    }

    // 옵션 종류 전체와 종류별 상품옵션 (상품 등록 화면의 옵션 선택 목록). 고정 경로라 /{postId}보다 먼저 매칭된다
    @GetMapping("/options")
    @Operation(operationId = "getProductOptions", summary = "옵션 종류 전체와 종류별 상품옵션")
    public ResponseEntity<RsData<List<ProductOptionTypeResponse>>> getProductOptions() {
        return ResponseEntity.ok(new RsData<>("200-1", "옵션 목록 조회에 성공했습니다.",
                productFacade.findProductOptions()));
    }

    // 옵션 종류 하나의 상품옵션 (옵션 종류를 고르면 옆 선택 목록을 채우는 용도)
    @GetMapping("/options/{typeId}")
    @Operation(operationId = "getProductOptionsByType", summary = "옵션 종류 하나의 상품옵션")
    public ResponseEntity<RsData<ProductOptionTypeResponse>> getProductOptionsByType(@PathVariable Long typeId) {
        return ResponseEntity.ok(new RsData<>("200-1", "옵션 목록 조회에 성공했습니다.",
                productFacade.findProductOptions(typeId)));
    }

    @PatchMapping("/{postId}/status")
    @Operation(operationId = "changeProductStatusByPost", summary = "판매글 단위 상품 판매 상태 변경 (내부 호출)")
    public ResponseEntity<Void> changeStatusByPost(@PathVariable Long postId,
                                                   @Valid @RequestBody ProductStatusChangeRequest request) {
        productFacade.changeStatusByPost(postId, request.status());
        return ResponseEntity.noContent().build();
    }
}
