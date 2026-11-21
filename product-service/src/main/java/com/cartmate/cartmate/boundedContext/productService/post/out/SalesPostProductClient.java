package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSnapshot;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateProductRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectProductResponse;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SyncProduct;
import com.cartmate.cartmate.boundedContext.productService.post.dto.UpdateSalesPostRequest;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 판매글이 상품 기능을 쓰는 창구. 판매글 코드는 이 인터페이스만 안다.
 *
 * <p>구현은 product.client 설정으로 고른다(application.yaml).
 * <ul>
 *   <li>local(기본): {@link LocalSalesPostProductClient} — 같은 서버 안에서 ProductFacade를 직접 부른다</li>
 *   <li>http: {@link SalesPostApiClient} — 상품 API를 HTTP로 부른다</li>
 * </ul>
 *
 * <p>서버 분리 시: 판매글과 상품이 다른 서버가 되면 product.client를 http로 바꾸고(PRODUCT_CLIENT 환경 변수),
 * PRODUCT_URL을 상품 서버 주소로 바꾼다. 판매글 코드는 고치지 않는다.
 *
 * <p>오류는 두 구현이 같게 낸다. 상품이 요청을 거절하면(4xx) ProductRequestRejectedException(상품 오류 코드 그대로),
 * 그 밖의 실패는 메서드별 실패 코드(ServiceException)다. 판매글 수정은 이 구분으로 복구 여부를 정한다.
 */
public interface SalesPostProductClient {

    // 판매글의 상품(사진 · 옵션 · 재고 포함)
    List<SelectProductResponse> getProduct(Long postId);

    // 상품을 저장하고 저장된 상품을 돌려준다. 판매글은 이걸로 복제본을 만든다
    List<SyncProduct> registerProducts(CreateProductRequest request, List<MultipartFile> images);

    // 상품을 수정하고 수정된 상품을 돌려준다. 판매글은 이걸로 복제본을 갱신한다
    List<SyncProduct> updateProducts(UpdateSalesPostRequest request, List<MultipartFile> images);

    // 판매글 수정 끝: 수정 중(UPDATING)인 상품을 스냅샷의 수정 전 상태로 되돌린다
    void completeProductUpdate(Long postId, SalesPostSnapshot snapshot);

    // 판매글 수정 실패 복구: 스냅샷으로 상품을 되돌리고 되돌린 상품을 돌려준다
    List<SyncProduct> restoreProducts(Long postId, SalesPostSnapshot snapshot);

    // 판매글 단위로 상품 판매 상태 변경 (판매자 내림 · 재개, 관리자 숨김 · 해제)
    void changeProductStatus(Long postId, SalesPostProductStatus status);
}
