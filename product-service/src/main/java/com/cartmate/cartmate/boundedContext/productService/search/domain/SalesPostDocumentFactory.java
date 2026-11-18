package com.cartmate.cartmate.boundedContext.productService.search.domain;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 판매글을 검색 문서로 바꾼다. 검색에 나오면 안 되는 판매글은 문서를 만들지 않는다. */
public final class SalesPostDocumentFactory {

    private SalesPostDocumentFactory() {
    }

    public static Optional<SalesPostDocument> create(SalesPost post, Map<Long, String> categoryNameById) {
        if (post.getStatus() != SalesPostSaleStatus.ON_SALE) {
            return Optional.empty();
        }
        List<SalesPostProduct> products = post.getProductList().stream()
                .filter(p -> p.getStatus() == SalesPostProductStatus.ON_SALE)
                .toList();
        // 살 수 있는 상품이 하나도 없으면 검색에 띄워도 들어가서 살 게 없다
        if (products.isEmpty()) {
            return Optional.empty();
        }

        List<String> categoryNames = products.stream().map(SalesPostProduct::getCategoryId)
                .filter(Objects::nonNull).distinct()
                .map(categoryNameById::get).filter(Objects::nonNull).distinct().toList();

        return Optional.of(new SalesPostDocument(
                post.getId(),
                post.getSellerId(),
                post.getTitle(),
                post.getContent(),
                products.stream().map(SalesPostProduct::getName).toList(),
                products.stream().map(SalesPostProduct::getDescription).filter(Objects::nonNull).toList(),
                categoryNames,
                products.stream().map(p -> new SalesPostDocument.Product(p.getCategoryId(), p.getBasePrice())).toList(),
                post.getCreateDate()));
    }
}
