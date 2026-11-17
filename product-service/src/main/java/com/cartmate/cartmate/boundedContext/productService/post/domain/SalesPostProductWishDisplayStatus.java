package com.cartmate.cartmate.boundedContext.productService.post.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 내 찜 목록에 보여줄 판매글 상품의 상태. 판매글 상태와 판매글 상품 상태를 합쳐서 정한다.
 * 삭제된 판매글 상품 · 삭제(중) · 등록 중인 판매글은 목록에서 빠지므로 여기 없다(EXCLUDED_*).
 */
@Getter
@RequiredArgsConstructor
public enum SalesPostProductWishDisplayStatus {
    ON_SALE("판매 중"),
    PAUSED("판매 중지"),      // 구매 · 장바구니 버튼 비활성
    CLOSED("판매 종료"),
    UNAVAILABLE("볼 수 없는 상품"); // 운영 중지. 상품명 · 이미지 · 가격을 가리고 찜 해제만 할 수 있다

    // 내 찜 목록에서 빼는 상태. 다시 판매될 일이 없어 숨겨도 찜이 깜빡이지 않는다
    public static final List<SalesPostSaleStatus> EXCLUDED_POST_STATUSES =
            List.of(SalesPostSaleStatus.DELETED, SalesPostSaleStatus.DELETING, SalesPostSaleStatus.PENDING);
    public static final List<SalesPostProductStatus> EXCLUDED_PRODUCT_STATUSES =
            List.of(SalesPostProductStatus.DELETED);

    private final String label;

    // 위에서부터 먼저 걸리는 쪽이 이긴다: 운영 중지 → 판매 종료 → 판매 중지 → 판매 중
    public static SalesPostProductWishDisplayStatus of(SalesPostSaleStatus postStatus,
                                                        SalesPostProductStatus productStatus) {
        if (postStatus == SalesPostSaleStatus.SUSPENDED || productStatus == SalesPostProductStatus.SUSPENDED) {
            return UNAVAILABLE;
        }
        if (postStatus == SalesPostSaleStatus.CLOSED || productStatus == SalesPostProductStatus.CLOSED) {
            return CLOSED;
        }
        if (postStatus == SalesPostSaleStatus.PAUSED
                || productStatus == SalesPostProductStatus.PAUSED
                || productStatus == SalesPostProductStatus.UPDATING) {
            return PAUSED;
        }
        return ON_SALE;
    }

    public boolean hidesContent() {
        return this == UNAVAILABLE;
    }
}
