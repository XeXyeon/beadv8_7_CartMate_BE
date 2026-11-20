package com.cartmate.cartmate.boundedContext.productService.post.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CreateSalesPostRequest {
    private Long id;
    private Long sellerId;
    private String title;
    private String content;
    private List<CreateSalesPostProduct> productList;

    // sellerMemberId는 요청 본문이 아니라 로그인 토큰에서 받는다. 본문 값은 클라이언트가 바꿀 수 있다
    public List<CreateProduct> toCreateProductList(Long sellerMemberId) {
        return productList.stream()
                .map(product -> product.toCreateProduct(sellerId, sellerMemberId))
                .toList();
    }
}
