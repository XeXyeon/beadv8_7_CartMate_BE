package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.code.ProductErrorCode;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostApiClient.ProductRequestRejectedException;
import com.cartmate.cartmate.boundedContext.productService.product.app.ProductFacade;
import com.cartmate.cartmate.boundedContext.productService.product.domain.ProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectInventoryStock;
import com.cartmate.cartmate.boundedContext.productService.product.dto.InventoryStock;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.rsData.RsData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 직접 호출 구현이 HTTP 구현(SalesPostApiClient)과 같은 오류를 내는지 본다.
 * 판매글 수정은 ProductRequestRejectedException이면 "상품이 이미 되돌렸다"로 보고 복구 없이 상태만 되돌리고,
 * 그 밖의 실패면 스냅샷 복구를 한다. 그래서 이 구분이 HTTP일 때와 같아야 한다.
 */
class LocalSalesPostProductClientTest {

    private final ProductFacade productFacade = mock(ProductFacade.class);
    private final LocalSalesPostProductClient client = new LocalSalesPostProductClient(productFacade);

    @Test
    @DisplayName("상품이 4xx 코드로 거절하면 상품의 코드 · 메시지 그대로 ProductRequestRejectedException")
    void rejectionKeepsProductCode() {
        willThrow(new ServiceException("409-PRODUCT_TEST", "재고를 선점 수량보다 적게 바꿀 수 없습니다."))
                .given(productFacade).changeStatusByPost(any(), any());

        assertThatThrownBy(() -> client.changeProductStatus(1L, SalesPostProductStatus.PAUSED))
                .isInstanceOf(ProductRequestRejectedException.class)
                .extracting("resultCode", "msg")
                .containsExactly("409-PRODUCT_TEST", "재고를 선점 수량보다 적게 바꿀 수 없습니다.");
    }

    @Test
    @DisplayName("상품이 5xx 코드로 실패하면 내부 메시지를 숨긴 실패 코드 (거절로 보지 않는다)")
    void serverErrorBecomesFailedCode() {
        willThrow(new ServiceException("500-PRODUCT_TEST", "내부 오류 상세"))
                .given(productFacade).changeStatusByPost(any(), any());

        assertThatThrownBy(() -> client.changeProductStatus(1L, SalesPostProductStatus.PAUSED))
                .isNotInstanceOf(ProductRequestRejectedException.class)
                .isInstanceOf(ServiceException.class)
                .extracting("resultCode")
                .isEqualTo(ProductErrorCode.PRODUCT_UPDATE_FAILED.resultCode());
    }

    @Test
    @DisplayName("ServiceException이 아닌 예외(DB 오류 등)도 실패 코드")
    void unexpectedErrorBecomesFailedCode() {
        willThrow(new IllegalStateException("DB 오류"))
                .given(productFacade).changeStatusByPost(any(), any());

        assertThatThrownBy(() -> client.changeProductStatus(1L, SalesPostProductStatus.PAUSED))
                .isNotInstanceOf(ProductRequestRejectedException.class)
                .extracting("resultCode")
                .isEqualTo(ProductErrorCode.PRODUCT_UPDATE_FAILED.resultCode());
    }

    @Test
    @DisplayName("재고 수량: 상품의 재고 · 선점 수량을 그대로 옮기고, 실패는 조회와 같은 코드")
    void getStocksMapsQuantities() {
        given(productFacade.selectStocksByPost(1L)).willReturn(new RsData<>("200-1", "ok",
                List.of(new InventoryStock(10L, 5, 2), new InventoryStock(11L, 1, 3))));
        willThrow(new IllegalStateException("DB 오류")).given(productFacade).selectStocksByPost(2L);

        assertThat(client.getStocks(1L))
                .extracting(SelectInventoryStock::inventoryId, SelectInventoryStock::availableQuantity)
                .containsExactly(tuple(10L, 3), tuple(11L, 0));   // 선점이 더 많으면 0
        assertThatThrownBy(() -> client.getStocks(2L))
                .extracting("resultCode")
                .isEqualTo(ProductErrorCode.PRODUCT_SELECT_FAILED.resultCode());
    }

    @Test
    @DisplayName("조회는 HTTP일 때처럼 거절이면 SELECT_INVALID, 실패면 SELECT_FAILED")
    void selectUsesFixedCodes() {
        willThrow(new ServiceException("404-PRODUCT_TEST", "없음"))
                .given(productFacade).selectProductsByPost(1L);
        willThrow(new IllegalStateException("DB 오류"))
                .given(productFacade).selectProductsByPost(2L);

        assertThatThrownBy(() -> client.getProduct(1L))
                .extracting("resultCode")
                .isEqualTo(ProductErrorCode.PRODUCT_SELECT_INVALID.resultCode());
        assertThatThrownBy(() -> client.getProduct(2L))
                .extracting("resultCode")
                .isEqualTo(ProductErrorCode.PRODUCT_SELECT_FAILED.resultCode());
    }

    @Test
    @DisplayName("판매 상태는 같은 이름의 상품 상태로 바꿔 넘긴다")
    void statusIsMappedByName() {
        client.changeProductStatus(1L, SalesPostProductStatus.SUSPENDED);

        verify(productFacade).changeStatusByPost(1L, ProductStatus.SUSPENDED);
    }
}
