package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductImage;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductOption;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductOptionRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SyncProduct;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.code.ProductErrorCode;
import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.shared.post.event.SalesPostChangedEvent;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SyncProductImage;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SyncProductOption;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SalesPostSyncProductUseCase {
    private final SalesPostRepository salesPostRepository;
    private final DomainEventPublisher domainEventPublisher;
    private final SalesPostProductRepository salesPostProductRepository;
    private final SalesPostProductImageRepository salesPostProductImageRepository;
    private final SalesPostProductOptionRepository salesPostProductOptionRepository;
    private final SalesPostDetailInventoryLoader salesPostDetailInventoryLoader;

    // product의 등록 · 수정 · 복구 응답으로 복제본을 저장한다(같은 ID면 덮어씀).
    // 부르는 쪽의 마무리 트랜잭션에 합쳐져서, 실패하면 그 작업(등록 · 수정 · 복구)도 실패한다
    @Transactional
    public void syncProduct(List<SyncProduct> syncList) {
        if (syncList == null || syncList.isEmpty()) {
            throw new ServiceException(ProductErrorCode.PRODUCT_SYNC_DATA_NOT_FOUND);
        }

        SalesPost salesPost = salesPostRepository.findById(syncList.getFirst().getSalesPostId())
                    .orElseThrow(() -> new ServiceException(SalesPostErrorCode.POST_NOT_FOUND));

        syncList.forEach(product -> {
            // 이미 있는 복제본(수정 · 복구)은 찾아서 값만 맞춘다. 같은 ID의 새 객체를 save(merge)하면
            // 비어 있는 사진 · 옵션 목록이 orphanRemoval로 기존 사진 · 옵션 복제본을 지워 목록 · 상세에서 사진이 사라졌다
            SalesPostProduct target = salesPostProductRepository.findById(product.getId())
                    .map(existing -> {
                        existing.syncFrom(product.getCategoryId(), product.getName(), product.getDescription(),
                                product.getBasePrice(), SalesPostProductStatus.ON_SALE, product.getModifyDate());
                        return existing;
                    })
                    .orElseGet(() -> salesPostProductRepository.save(new SalesPostProduct(
                            product.getId(),
                            product.getSellerId(),
                            salesPost,
                            product.getCategoryId(),
                            product.getName(),
                            product.getDescription(),
                            product.getBasePrice(),
                            SalesPostProductStatus.ON_SALE,
                            product.getCreateDate(),
                            product.getModifyDate()
                    )));

            List<SyncProductImage> imageList = product.getProductImageList();
            if (imageList != null) {
                imageList.forEach(image -> syncImage(image, target));
            }

            List<SyncProductOption> optionList = product.getProductOptionList();
            if (optionList != null) {
                optionList.forEach(option -> syncOption(option, target));
            }
        });
        domainEventPublisher.publish(new SalesPostChangedEvent(salesPost.getId()));
        // 상품이 이미 바뀐 값을 커밋한 뒤라, 다음 상세 조회가 바뀐 재고 구성 · 가격 · 상품옵션을 다시 읽는다
        salesPostDetailInventoryLoader.evict(salesPost.getId());
    }

    // 같은 ID의 사진 복제본이 있으면 값을 맞추고(빠진 사진은 DELETED로 온다), 없으면 새로 만든다
    private void syncImage(SyncProductImage image, SalesPostProduct product) {
        salesPostProductImageRepository.findById(image.getId())
                .ifPresentOrElse(
                        existing -> existing.syncFrom(image),
                        () -> salesPostProductImageRepository.save(SalesPostProductImage.from(image, product)));
    }

    // 같은 ID의 선택된 옵션 복제본이 있으면 값을 맞추고, 없으면 새로 만든다
    private void syncOption(SyncProductOption option, SalesPostProduct product) {
        salesPostProductOptionRepository.findById(option.getId())
                .ifPresentOrElse(
                        existing -> existing.syncFrom(option),
                        () -> salesPostProductOptionRepository.save(SalesPostProductOption.from(option, product)));
    }

    // 판매글 삭제로 product에서 지워진 상품의 복제본을 지운다(소프트 삭제라 다시 와도 결과가 같다)
    @Transactional
    public void deleteProductByPost(List<Long> productIds) {
        salesPostProductRepository.findByIdIn(productIds)
                .forEach(SalesPostProduct::delete);
    }

    // 판매자가 지운 상품 하나의 복제본을 지운다(소프트 삭제라 다시 와도 결과가 같다)
    @Transactional
    public void deleteProductByUser(Long productId) {
        salesPostProductRepository.findById(productId)
                .ifPresent(SalesPostProduct::delete);
    }

}
