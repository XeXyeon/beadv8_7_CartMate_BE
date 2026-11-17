package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductWish;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductWishDisplayStatus;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SalesPostProductWishResponse;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductWishQueryRepository.WishRow;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductWishRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 판매글 상품 찜하기 · 찜 해제. 둘 다 같은 요청을 다시 보내도 결과가 같다.
 * 같은 찜 요청이 동시에 들어와 유니크 키에 막히는 경우는 SalesPostFacade가 이미 찜한 것으로 처리한다.
 */
@Service
@RequiredArgsConstructor
public class SalesPostProductWishUseCase {
    public static final int PAGE_SIZE = 20;

    private final SalesPostProductRepository salesPostProductRepository;
    private final SalesPostProductWishRepository salesPostProductWishRepository;

    // 이미 찜한 상품이면 상태와 관계없이 그대로 성공한다
    @Transactional
    public void wish(Long memberId, Long productId) {
        if (salesPostProductWishRepository.existsByMemberIdAndSalesPostProductId(memberId, productId)) {
            return;
        }
        findWishableProduct(productId);
        salesPostProductWishRepository.saveAndFlush(SalesPostProductWish.of(memberId, productId));
    }

    // 찜 해제는 판매글 · 판매글 상품 상태와 관계없이 언제든 된다. 찜하지 않은 상품이어도 성공한다
    @Transactional
    public void unwish(Long memberId, Long productId) {
        salesPostProductWishRepository.deleteByMemberIdAndSalesPostProductId(memberId, productId);
    }

    // 내 찜 목록. 찜한 순서(최근 찜 먼저)로 한 페이지에 PAGE_SIZE개.
    // 대표 이미지는 그 페이지 상품들 것을 한 번에 가져온다
    @Transactional(readOnly = true)
    public PageResponse<SalesPostProductWishResponse> selectMyWishes(Long memberId, int page) {
        Page<WishRow> rows = salesPostProductWishRepository.findMyWishes(
                memberId, PageRequest.of(Math.max(page, 0), PAGE_SIZE));
        Map<Long, String> thumbnails = salesPostProductWishRepository.findThumbnailS3Keys(
                rows.getContent().stream().map(WishRow::productId).toList());
        return PageResponse.of(rows.map(row -> toResponse(row, thumbnails.get(row.productId()))));
    }

    // 볼 수 없는 상품(운영 중지)은 상품 내용을 비우고 id만 준다
    private SalesPostProductWishResponse toResponse(WishRow row, String thumbnailS3Key) {
        SalesPostProductWishDisplayStatus displayStatus =
                SalesPostProductWishDisplayStatus.of(row.postStatus(), row.productStatus());
        if (displayStatus.hidesContent()) {
            return new SalesPostProductWishResponse(row.salesPostId(), row.productId(), null, null, null,
                    displayStatus, row.wishedAt());
        }
        return new SalesPostProductWishResponse(row.salesPostId(), row.productId(), row.productName(),
                thumbnailS3Key, row.basePrice(), displayStatus, row.wishedAt());
    }

    // 다른 사용자에게 보이지 않는 판매글의 상품 · 삭제된 판매글 상품은 없는 상품으로,
    // 보이지만 새로 찜할 수 없는 판매글(판매 종료)의 상품은 찜 불가로 응답한다
    private SalesPostProduct findWishableProduct(Long productId) {
        SalesPostProduct product = salesPostProductRepository.findById(productId)
                .filter(p -> p.getStatus() != SalesPostProductStatus.DELETED)
                .filter(p -> p.getSalesPost().getStatus().isPubliclyVisible())
                .orElseThrow(() -> new ServiceException(SalesPostErrorCode.WISH_PRODUCT_NOT_FOUND));
        SalesPost post = product.getSalesPost();
        if (!post.getStatus().isWishable()) {
            throw new ServiceException(SalesPostErrorCode.WISH_NOT_ALLOWED);
        }
        return product;
    }
}
