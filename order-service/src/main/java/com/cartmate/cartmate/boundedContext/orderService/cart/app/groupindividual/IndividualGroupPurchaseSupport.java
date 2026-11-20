package com.cartmate.cartmate.boundedContext.orderService.cart.app.groupindividual;

import com.cartmate.cartmate.boundedContext.orderService.cart.exception.CartErrorCode;
import com.cartmate.cartmate.boundedContext.orderService.cart.out.CartProductOptions;
import com.cartmate.cartmate.boundedContext.orderService.cart.out.ProductClient;
import com.cartmate.cartmate.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
class IndividualGroupPurchaseSupport {

    private final ProductClient productClient;

    void validatePurchasableProducts(Collection<Long> productIds) {
        validatePurchasable(productOptionsOf(productIds), productIds);
    }

    // 판매자가 자기 상품으로 공동구매를 열면, 링크를 돌린 뒤 주문할 때 가서야 막힌다. 그래서 열거나 상품을 더할 때 막는다
    void validatePurchasableProductsFor(Long memberId, Collection<Long> productIds) {
        Map<Long, CartProductOptions> found = productOptionsOf(productIds);
        validatePurchasable(found, productIds);
        if (found.values().stream().anyMatch(info -> memberId.equals(info.sellerMemberId()))) {
            throw new ServiceException(CartErrorCode.CART_OWN_PRODUCT);
        }
    }

    private void validatePurchasable(Map<Long, CartProductOptions> found, Collection<Long> productIds) {
        for (Long productId : productIds.stream().distinct().toList()) {
            CartProductOptions info = found.get(productId);
            if (info == null) {
                throw new ServiceException(CartErrorCode.CART_PRODUCT_NOT_FOUND);
            }
            if (!info.purchasable()) {
                throw new ServiceException(CartErrorCode.CART_PRODUCT_NOT_ON_SALE);
            }
        }
    }

    Map<Long, CartProductOptions> productOptionsOf(Collection<Long> productIds) {
        return productClient.findAllProductOptions(productIds).stream()
                .collect(Collectors.toMap(CartProductOptions::productId, Function.identity()));
    }
}
