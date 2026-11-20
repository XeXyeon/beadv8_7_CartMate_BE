package com.cartmate.cartmate.boundedContext.orderService.cart.app.groupshared;

import com.cartmate.cartmate.boundedContext.orderService.cart.domain.groupshared.SharedGroupPurchase;
import com.cartmate.cartmate.boundedContext.orderService.cart.domain.groupshared.SharedGroupPurchaseItem;
import com.cartmate.cartmate.boundedContext.orderService.cart.exception.CartErrorCode;
import com.cartmate.cartmate.boundedContext.orderService.cart.out.AbuseRestrictionClient;
import com.cartmate.cartmate.boundedContext.orderService.cart.out.CartProductInfo;
import com.cartmate.cartmate.boundedContext.orderService.cart.out.ProductClient;
import com.cartmate.cartmate.boundedContext.orderService.cart.out.groupshared.SharedGroupPurchaseRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.shared.abuse.dto.AbuseRestrictionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
class SharedGroupPurchaseSupport {

    private static final DateTimeFormatter RESTRICTED_UNTIL_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SharedGroupPurchaseRepository sharedGroupPurchaseRepository;
    private final ProductClient productClient;
    private final AbuseRestrictionClient abuseRestrictionClient;

    SharedGroupPurchase getPurchaseForUpdate(Long sharedGroupPurchaseId) {
        return sharedGroupPurchaseRepository.findByIdForUpdate(sharedGroupPurchaseId)
                .orElseThrow(() -> new ServiceException(CartErrorCode.CART_GROUP_PURCHASE_NOT_FOUND));
    }

    void validateNotRestricted(Long memberId) {
        AbuseRestrictionStatus restriction = abuseRestrictionClient.getRestriction(memberId);
        if (!restriction.restricted()) {
            return;
        }
        CartErrorCode errorCode = CartErrorCode.CART_MEMBER_RESTRICTED;
        throw new ServiceException(errorCode.resultCode(),
                errorCode.getMsg() + " 제한 종료: " + restriction.restrictedUntil().format(RESTRICTED_UNTIL_FORMAT));
    }

    Set<Long> findUnavailableInventoryIds(SharedGroupPurchase purchase) {
        return productClient.findUnavailableInventoryIds(inventoryIdsOf(purchase));
    }

    // 방에 담긴 품목 중 이 회원이 판매하는 상품의 재고 ID
    Set<Long> findOwnInventoryIds(SharedGroupPurchase purchase, Long memberId) {
        return productClient.findAllByInventoryIds(inventoryIdsOf(purchase)).stream()
                .filter(product -> memberId.equals(product.sellerMemberId()))
                .map(CartProductInfo::inventoryId)
                .collect(Collectors.toSet());
    }

    List<Long> inventoryIdsOf(SharedGroupPurchase purchase) {
        return purchase.getItems().stream()
                .map(SharedGroupPurchaseItem::getInventoryId)
                .toList();
    }
}
