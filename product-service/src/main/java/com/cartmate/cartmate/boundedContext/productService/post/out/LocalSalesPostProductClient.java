package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.code.ProductErrorCode;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSnapshot;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateProductRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectInventoryStock;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectProductResponse;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SyncProduct;
import com.cartmate.cartmate.boundedContext.productService.post.dto.UpdateSalesPostRequest;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostApiClient.ProductRequestRejectedException;
import com.cartmate.cartmate.boundedContext.productService.product.app.ProductFacade;
import com.cartmate.cartmate.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.function.Supplier;

/**
 * 판매글 → 상품 직접 호출 구현 (product.client=local, 기본값).
 *
 * <p>같은 서버 안에서 ProductFacade를 바로 부른다. HTTP 요청 · 응답 처리와 내부 전용 포트(9982)가 없어
 * 판매글 상세가 내부 요청을 기다리며 자원을 쥐는 교착상태가 생기지 않는다.
 *
 * <p>트랜잭션: 호출한 쪽의 트랜잭션을 그대로 따른다.
 * <ul>
 *   <li>등록 · 수정 · 복구 · 수정 끝 · 조회: 판매글이 트랜잭션 밖에서 부르므로 상품 쪽이 자기 트랜잭션으로 따로 커밋한다(HTTP일 때와 같다)</li>
 *   <li>판매 상태 변경: 판매 중지 · 재개, 관리자 숨김 · 해제가 @Transactional 안에서 부르므로 판매글 변경과 한 트랜잭션이 된다.
 *       둘 중 하나가 실패하면 둘 다 되돌아가 상품 · 판매글 상태가 어긋나지 않는다</li>
 * </ul>
 *
 * <p>오류는 SalesPostApiClient(HTTP)와 같은 코드로 바꿔 던진다. HTTP에서 상품이 4xx로 응답하던 경우 = 상품이 4xx 코드의
 * ServiceException을 던진 경우다.
 *
 * <p>서버 분리 시: product.client를 http로 바꾸면 이 구현은 만들어지지 않는다. 분리가 끝나 상품 코드가 이 서버에 없으면 이 파일을 지운다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "product.client", havingValue = "local", matchIfMissing = true)
public class LocalSalesPostProductClient implements SalesPostProductClient {

    private final ProductFacade productFacade;

    @Override
    public List<SelectProductResponse> getProduct(Long postId) {
        return call(() -> SalesPostProductMapper.fromProductSelect(productFacade.selectProductsByPost(postId).getData()),
                ProductErrorCode.PRODUCT_SELECT_INVALID, ProductErrorCode.PRODUCT_SELECT_FAILED);
    }

    @Override
    public List<SelectInventoryStock> getStocks(Long postId) {
        return call(() -> SalesPostProductMapper.fromProductStock(productFacade.selectStocksByPost(postId).getData()),
                ProductErrorCode.PRODUCT_SELECT_INVALID, ProductErrorCode.PRODUCT_SELECT_FAILED);
    }

    @Override
    public List<SyncProduct> registerProducts(CreateProductRequest request, List<MultipartFile> images) {
        return callKeepingRejection(() -> SalesPostProductMapper.fromProductSync(
                        productFacade.registerProduct(SalesPostProductMapper.toProductCreate(request), images)),
                ProductErrorCode.PRODUCT_CREATE_FAILED);
    }

    @Override
    public List<SyncProduct> updateProducts(UpdateSalesPostRequest request, List<MultipartFile> images) {
        return callKeepingRejection(() -> SalesPostProductMapper.fromProductSync(
                        productFacade.updateProducts(SalesPostProductMapper.toProductUpdate(request), images)),
                ProductErrorCode.PRODUCT_UPDATE_FAILED);
    }

    // HTTP에서도 어떤 오류든 PRODUCT_UPDATE_FAILED였다(실패하면 판매글이 스냅샷 복구를 한다)
    @Override
    public void completeProductUpdate(Long postId, SalesPostSnapshot snapshot) {
        var request = SalesPostProductMapper.toProductComplete(SalesPostProductMapper.toCompleteRequest(snapshot));
        call(() -> {
            productFacade.completeProductUpdate(postId, request);
            return null;
        }, ProductErrorCode.PRODUCT_UPDATE_FAILED, ProductErrorCode.PRODUCT_UPDATE_FAILED);
    }

    @Override
    public List<SyncProduct> restoreProducts(Long postId, SalesPostSnapshot snapshot) {
        var request = SalesPostProductMapper.toProductRestore(SalesPostProductMapper.toRestoreRequest(postId, snapshot));
        return call(() -> SalesPostProductMapper.fromProductSync(productFacade.restoreProducts(postId, request)),
                ProductErrorCode.PRODUCT_RESTORE_INVALID, ProductErrorCode.PRODUCT_RESTORE_FAILED);
    }

    @Override
    public void changeProductStatus(Long postId, SalesPostProductStatus status) {
        callKeepingRejection(() -> {
            productFacade.changeStatusByPost(postId, SalesPostProductMapper.toProductStatus(status));
            return null;
        }, ProductErrorCode.PRODUCT_UPDATE_FAILED);
    }

    // 상품의 거절 사유(코드 · 메시지)를 판매자에게 그대로 보여 주는 호출 (HTTP의 exchange와 같음)
    // - 4xx 코드 ServiceException → ProductRequestRejectedException (상품이 자기 쪽을 되돌린 "확실한 실패")
    // - 5xx 코드 ServiceException · 그 밖의 예외 → failed (내부 메시지를 판매자에게 보이지 않게)
    private <T> T callKeepingRejection(Supplier<T> action, ProductErrorCode failed) {
        try {
            return action.get();
        } catch (ServiceException e) {
            if (isRejected(e)) {
                throw new ProductRequestRejectedException(e.getResultCode(), e.getMsg());
            }
            log.error("상품 처리 실패: {}", e.getResultCode(), e);
            throw new ServiceException(failed);
        } catch (RuntimeException e) {
            log.error("상품 처리 실패", e);
            throw new ServiceException(failed);
        }
    }

    // 거절 · 실패를 판매글 쪽 고정 코드로 바꾸는 호출 (HTTP의 onStatus 4xx / 5xx와 같음)
    private <T> T call(Supplier<T> action, ProductErrorCode rejected, ProductErrorCode failed) {
        try {
            return action.get();
        } catch (ServiceException e) {
            if (isRejected(e)) {
                throw new ServiceException(rejected);
            }
            log.error("상품 처리 실패: {}", e.getResultCode(), e);
            throw new ServiceException(failed);
        } catch (RuntimeException e) {
            log.error("상품 처리 실패", e);
            throw new ServiceException(failed);
        }
    }

    // HTTP에서 4xx로 응답했을 오류인지 (resultCode 앞자리 = 응답 상태 코드, GlobalExceptionHandler와 같은 규칙)
    private static boolean isRejected(ServiceException e) {
        int status = e.getRsData().getStatusCode();
        return status >= 400 && status < 500;
    }
}
