package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
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
import com.cartmate.cartmate.boundedContext.productService.product.domain.Product;
import com.cartmate.cartmate.boundedContext.productService.product.domain.ProductStatus;
import com.cartmate.cartmate.boundedContext.productService.product.out.FakeImageStorage;
import com.cartmate.cartmate.boundedContext.productService.product.out.ProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.product.out.ProductOptionRepository;
import com.cartmate.cartmate.boundedContext.productService.product.out.ProductRepository;
import com.cartmate.cartmate.support.ProductServiceTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static com.cartmate.cartmate.support.SeedOptions.*;
import static com.cartmate.cartmate.support.TestInventories.inventory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;

/**
 * 판매 중지는 상품 상태 변경과 판매글 변경을 한 트랜잭션으로 처리한다(product.client=local, 직접 호출).
 * 상품 상태를 바꾼 뒤 판매글 쪽에서 실패하면 상품 상태도 같이 되돌아가는지 본다.
 * (HTTP일 때는 상품이 먼저 따로 커밋되어 상품만 PAUSED로 남을 수 있었다)
 */
class SalesPostHideTransactionTest extends ProductServiceTestSupport {

    private static final Long SELLER_ID = 7L;

    @Autowired SalesPostFacade salesPostFacade;
    @Autowired SalesPostHideUseCase hideUseCase;
    @Autowired SalesPostRepository salesPostRepository;
    @Autowired SalesPostProductRepository salesPostProductRepository;
    @Autowired SalesPostProductImageRepository salesPostProductImageRepository;
    @Autowired SalesPostProductOptionRepository salesPostProductOptionRepository;
    @Autowired ProductRepository productRepository;
    @Autowired ProductImageRepository productImageRepository;
    @Autowired ProductOptionRepository productOptionRepository;
    @Autowired FakeImageStorage storage;

    @BeforeEach
    void setUp() {
        salesPostProductImageRepository.deleteAll();
        salesPostProductOptionRepository.deleteAll();
        salesPostProductRepository.deleteAll();
        salesPostRepository.deleteAll();
        productImageRepository.deleteAll();
        productOptionRepository.deleteAll();
        productRepository.deleteAll();
        storage.reset();
    }

    @Test
    @DisplayName("성공: 판매 중지하면 판매글과 상품이 모두 PAUSED")
    void pauseChangesBoth() {
        Long postId = registerPost();

        hideUseCase.pauseBySeller(postId, SELLER_ID, SELLER_ID);

        assertThat(postStatus(postId)).isEqualTo(SalesPostSaleStatus.PAUSED);
        assertThat(productStatuses()).containsOnly(ProductStatus.PAUSED);
    }

    @Test
    @DisplayName("실패: 상품 상태를 바꾼 뒤 판매글 쪽에서 실패하면 상품 상태도 ON_SALE로 되돌아간다")
    void productStatusRollsBackWithPost() {
        Long postId = registerPost();
        // 상품 상태 변경은 실제로 실행한 뒤, 그 다음 판매글 처리에서 실패한 것처럼 예외를 던진다
        willAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("판매글 저장 실패 흉내");
        }).given(salesPostApiClient).changeProductStatus(any(), any());

        assertThatThrownBy(() -> hideUseCase.pauseBySeller(postId, SELLER_ID, SELLER_ID))
                .isInstanceOf(IllegalStateException.class);

        assertThat(postStatus(postId)).isEqualTo(SalesPostSaleStatus.ON_SALE);
        assertThat(productStatuses()).containsOnly(ProductStatus.ON_SALE);
    }

    private SalesPostSaleStatus postStatus(Long postId) {
        return salesPostRepository.findById(postId).map(SalesPost::getStatus).orElseThrow();
    }

    private List<ProductStatus> productStatuses() {
        return productRepository.findAll().stream().map(Product::getStatus).toList();
    }

    private Long registerPost() {
        List<CreateSalesPostProductImage> productImages = List.of(
                new CreateSalesPostProductImage(null, null, null, 0, null));
        List<CreateInventory> inventories = List.of(inventory(
                new CreateProductOption(CLOTHING_SIZE, SIZE_L), new CreateProductOption(COLOR, BLACK)));
        CreateSalesPostProduct product = new CreateSalesPostProduct(
                null, null, 1L, "아이폰", "설명", 10000L, productImages, inventories);
        salesPostFacade.registerSalesPost(
                new CreateSalesPostRequest(null, SELLER_ID, "아이폰 팝니다", "상태 좋아요", List.of(product)),
                List.of(TestImageFiles.jpeg()), 100L);
        return salesPostRepository.findAll().getFirst().getId();
    }
}
