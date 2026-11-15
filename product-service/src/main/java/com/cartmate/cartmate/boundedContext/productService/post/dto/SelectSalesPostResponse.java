package com.cartmate.cartmate.boundedContext.productService.post.dto;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SellerTrustLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SelectSalesPostResponse {
    private Long id;
    private Long sellerId;
    private Integer sellerTrustScore;          // 판매자 신뢰도 점수. 점수가 없는 NEW 등급이면 null
    private SellerTrustLevel sellerTrustLevel; // 판매자 신뢰도 등급. 신뢰도 정보가 없는 판매자는 NEW
    private String title;
    private String content;
    private Long viewCount;
    private SalesPostSaleStatus status;
    private SalesPostUpdateStatus updateStatus; // 수정 진행 상태. 수정 중(PENDING) · 복구 중(RECOVERING)이면 수정 · 판매 중지 버튼을 막는다
    private List<SelectSalesPostProduct> productList;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
