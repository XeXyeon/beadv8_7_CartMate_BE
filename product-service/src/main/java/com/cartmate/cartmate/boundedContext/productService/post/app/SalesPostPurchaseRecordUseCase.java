package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostPurchase;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostPurchaseRepository;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 리뷰 자격용 구매 기록 저장(결제 완료 · 공동 주문 확정)과 구매확정 표시(구매확정).
 *
 * <p>이 이벤트들은 재고 판매 확정 · 페이백 반영과 같은 트랜잭션(Kafka 인박스 처리)에서 받는다.
 * 구매 기록 저장이 실패해도 그쪽이 롤백되지 않도록 별도 트랜잭션(REQUIRES_NEW)으로 처리하고,
 * 예외는 리스너(SalesPostEventListener)가 로그만 남기고 삼킨다.
 */
@Service
@RequiredArgsConstructor
public class SalesPostPurchaseRecordUseCase {
    private final SalesPostPurchaseRepository salesPostPurchaseRepository;

    // 주문 하나의 주문항목들을 구매 기록에 저장한다. 구매확정이 먼저 와서 줄이 있으면 구매 정보만 채운다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPurchases(PurchaseType purchaseType, Long orderId, Long buyerMemberId, List<PurchasedItem> items) {
        Map<Long, SalesPostPurchase> existing = findExisting(purchaseType,
                items.stream().map(PurchasedItem::orderItemId).toList());

        List<SalesPostPurchase> toSave = items.stream()
                .map(item -> {
                    SalesPostPurchase purchase = existing.get(item.orderItemId());
                    if (purchase == null) {
                        return SalesPostPurchase.purchased(purchaseType, orderId, item.orderItemId(), buyerMemberId,
                                item.productId(), item.inventoryId(), item.sellerId(),
                                item.productName(), item.optionName());
                    }
                    purchase.fillPurchase(buyerMemberId, item.productId(), item.inventoryId(), item.sellerId(),
                            item.productName(), item.optionName());
                    return purchase;
                })
                .toList();
        salesPostPurchaseRepository.saveAllAndFlush(toSave);
    }

    // 구매확정된 주문항목들에 확정 시각을 채운다. 결제 완료보다 먼저 오면 확정 시각만 있는 줄을 만든다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void confirmPurchases(PurchaseType purchaseType, Long orderId, List<Long> orderItemIds,
                                 LocalDateTime confirmedAt) {
        Map<Long, SalesPostPurchase> existing = findExisting(purchaseType, orderItemIds);

        List<SalesPostPurchase> toSave = orderItemIds.stream()
                .distinct()
                .map(orderItemId -> {
                    SalesPostPurchase purchase = existing.get(orderItemId);
                    if (purchase == null) {
                        return SalesPostPurchase.confirmedFirst(purchaseType, orderId, orderItemId, confirmedAt);
                    }
                    purchase.confirm(confirmedAt);
                    return purchase;
                })
                .toList();
        salesPostPurchaseRepository.saveAllAndFlush(toSave);
    }

    private Map<Long, SalesPostPurchase> findExisting(PurchaseType purchaseType, List<Long> orderItemIds) {
        return salesPostPurchaseRepository.findByPurchaseTypeAndOrderItemIdIn(purchaseType, orderItemIds).stream()
                .collect(Collectors.toMap(SalesPostPurchase::getOrderItemId, Function.identity()));
    }

    // 결제 완료 · 공동 주문 확정 이벤트의 주문항목 하나
    public record PurchasedItem(
            Long orderItemId,
            Long productId,
            Long inventoryId,
            Long sellerId,
            String productName,
            String optionName
    ) {
    }
}
