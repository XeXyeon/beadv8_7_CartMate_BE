package com.cartmate.cartmate.boundedContext.orderService.cart.app.groupshared;

import com.cartmate.cartmate.boundedContext.orderService.cart.app.InviteCodeGenerator;
import com.cartmate.cartmate.boundedContext.orderService.cart.domain.groupshared.SharedGroupPurchase;
import com.cartmate.cartmate.boundedContext.orderService.cart.domain.groupshared.SharedGroupPurchaseItem;
import com.cartmate.cartmate.boundedContext.orderService.cart.dto.groupshared.SharedGroupPurchaseCreateResponse;
import com.cartmate.cartmate.boundedContext.orderService.cart.exception.CartErrorCode;
import com.cartmate.cartmate.boundedContext.orderService.cart.out.AbuseRestrictionClient;
import com.cartmate.cartmate.boundedContext.orderService.cart.out.CartProductInfo;
import com.cartmate.cartmate.boundedContext.orderService.cart.out.ProductClient;
import com.cartmate.cartmate.boundedContext.orderService.cart.out.groupshared.SharedGroupPurchaseRepository;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.shared.abuse.dto.AbuseRestrictionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;

/**
 * 공동물품 구매에서 판매자의 자기 상품 구매 막기.
 * 참가자 전원이 분담금을 내야 확정되므로, 방의 참가자와 담긴 상품의 판매자가 겹치는 상태를 처음부터 만들지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class SharedGroupPurchaseSelfPurchaseTest {

    private static final Clock CLOCK = Clock.fixed(
            LocalDateTime.of(2026, 11, 20, 12, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    private static final Long CREATOR = 1L;
    private static final Long SELLER = 2L;
    private static final Long OTHER_SELLER = 3L;
    private static final String INVITE_CODE = "abcdefghijkl";

    // SELLER가 파는 유니폼(재고 11, 12)과 OTHER_SELLER가 파는 축구공(재고 21)
    private static final CartProductInfo UNIFORM_M = product(11L, 100L, SELLER, "M");
    private static final CartProductInfo UNIFORM_L = product(12L, 100L, SELLER, "L");
    private static final CartProductInfo BALL = product(21L, 200L, OTHER_SELLER, "5호");

    @Mock
    private SharedGroupPurchaseRepository repository;
    @Mock
    private ProductClient productClient;
    @Mock
    private AbuseRestrictionClient abuseRestrictionClient;
    @Mock
    private DomainEventPublisher eventPublisher;
    @Mock
    private InviteCodeGenerator inviteCodeGenerator;

    private SharedGroupPurchaseItemUseCase itemUseCase;
    private SharedGroupPurchaseMemberUseCase memberUseCase;
    private SharedGroupPurchaseCreateUseCase createUseCase;

    @BeforeEach
    void setUp() {
        SharedGroupPurchaseSupport support = new SharedGroupPurchaseSupport(repository, productClient, abuseRestrictionClient);
        itemUseCase = new SharedGroupPurchaseItemUseCase(support, productClient, CLOCK);
        memberUseCase = new SharedGroupPurchaseMemberUseCase(repository, support, eventPublisher, CLOCK);
        createUseCase = new SharedGroupPurchaseCreateUseCase(repository, support, inviteCodeGenerator, CLOCK);
        lenient().when(abuseRestrictionClient.getRestriction(anyLong()))
                .thenAnswer(invocation -> new AbuseRestrictionStatus(invocation.getArgument(0), false, null));
        lenient().when(productClient.findByInventoryId(anyLong()))
                .thenAnswer(invocation -> catalog(List.of(invocation.<Long>getArgument(0))).stream().findFirst());
        lenient().when(productClient.findAllByInventoryIds(anyCollection()))
                .thenAnswer(invocation -> catalog(invocation.getArgument(0)));
    }

    @Test
    @DisplayName("참가자가 파는 상품은 다른 참가자가 담아도 막힌다")
    void otherMemberCannotAddParticipantSellersProduct() {
        SharedGroupPurchase purchase = room();
        purchase.join(SELLER, NOW);
        given(repository.findByIdForUpdate(10L)).willReturn(Optional.of(purchase));

        assertThatThrownBy(() -> itemUseCase.addItem(CREATOR, 10L, UNIFORM_M.inventoryId(), 1))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getResultCode())
                        .isEqualTo(CartErrorCode.CART_SHARED_SELLER_PARTICIPANT.resultCode()));
        assertThat(purchase.getItems()).isEmpty();
    }

    @Test
    @DisplayName("판매자 본인이 자기 상품을 담아도 막힌다")
    void sellerCannotAddOwnProduct() {
        SharedGroupPurchase purchase = room();
        purchase.join(SELLER, NOW);
        given(repository.findByIdForUpdate(10L)).willReturn(Optional.of(purchase));

        assertThatThrownBy(() -> itemUseCase.addItem(SELLER, 10L, UNIFORM_M.inventoryId(), 1))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getResultCode())
                        .isEqualTo(CartErrorCode.CART_SHARED_SELLER_PARTICIPANT.resultCode()));
    }

    @Test
    @DisplayName("판매자가 참가자가 아니면 그 상품을 담을 수 있다")
    void canAddWhenSellerIsNotParticipant() {
        SharedGroupPurchase purchase = room();
        purchase.join(SELLER, NOW);
        given(repository.findByIdForUpdate(10L)).willReturn(Optional.of(purchase));

        itemUseCase.addItem(SELLER, 10L, BALL.inventoryId(), 1);

        assertThat(purchase.getItems()).extracting(SharedGroupPurchaseItem::getInventoryId)
                .containsExactly(BALL.inventoryId());
    }

    @Test
    @DisplayName("옵션을 바꿀 때도 바꿀 상품의 판매자가 참가자면 막힌다")
    void cannotChangeOptionToParticipantSellersProduct() {
        SharedGroupPurchase purchase = room();
        purchase.addItem(CREATOR, UNIFORM_M.productId(), UNIFORM_M.inventoryId(), 1, NOW);
        purchase.join(SELLER, NOW);
        Long itemId = 7L;
        ReflectionTestUtils.setField(purchase.getItems().getFirst(), "id", itemId);
        given(repository.findByIdForUpdate(10L)).willReturn(Optional.of(purchase));

        assertThatThrownBy(() -> itemUseCase.changeItemOption(CREATOR, 10L, itemId, UNIFORM_L.inventoryId()))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getResultCode())
                        .isEqualTo(CartErrorCode.CART_SHARED_SELLER_PARTICIPANT.resultCode()));
        assertThat(purchase.getItems()).extracting(SharedGroupPurchaseItem::getInventoryId)
                .containsExactly(UNIFORM_M.inventoryId());
    }

    @Test
    @DisplayName("자기 상품이 담긴 방에는 참가할 수 없고, 다른 사람은 참가할 수 있다")
    void sellerCannotJoinRoomWithOwnProduct() {
        SharedGroupPurchase purchase = room();
        purchase.addItem(CREATOR, UNIFORM_M.productId(), UNIFORM_M.inventoryId(), 1, NOW);
        given(repository.findByInviteCodeForUpdate(INVITE_CODE)).willReturn(Optional.of(purchase));

        assertThatThrownBy(() -> memberUseCase.join(SELLER, INVITE_CODE))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getResultCode())
                        .isEqualTo(CartErrorCode.CART_SHARED_OWN_PRODUCT_INCLUDED.resultCode()));
        assertThat(purchase.isMember(SELLER)).isFalse();

        memberUseCase.join(OTHER_SELLER, INVITE_CODE);
        assertThat(purchase.isMember(OTHER_SELLER)).isTrue();
    }

    @Test
    @DisplayName("복제할 때 개설자 본인이 파는 상품은 빼고 복사하고, 뺀 재고를 응답으로 알린다")
    void duplicateExcludesCreatorsOwnProduct() {
        SharedGroupPurchase source = SharedGroupPurchase.create("주말 축구", SELLER, 5, INVITE_CODE, NOW);
        source.addItem(SELLER, UNIFORM_M.productId(), UNIFORM_M.inventoryId(), 1, NOW);
        source.addItem(SELLER, BALL.productId(), BALL.inventoryId(), 2, NOW);
        source.cancel(SELLER, NOW);
        given(repository.findById(10L)).willReturn(Optional.of(source));
        given(inviteCodeGenerator.generateUnique(any())).willReturn("newinvite123");
        given(repository.save(any(SharedGroupPurchase.class))).willAnswer(invocation -> invocation.getArgument(0));

        SharedGroupPurchaseCreateResponse response = createUseCase.duplicate(SELLER, 10L);

        assertThat(response.excludedInventoryIds()).containsExactly(UNIFORM_M.inventoryId());
        ArgumentCaptor<SharedGroupPurchase> copy = ArgumentCaptor.forClass(SharedGroupPurchase.class);
        then(repository).should().save(copy.capture());
        assertThat(copy.getValue().getItems()).extracting(SharedGroupPurchaseItem::getInventoryId)
                .containsExactly(BALL.inventoryId());
    }

    private SharedGroupPurchase room() {
        return SharedGroupPurchase.create("주말 축구", CREATOR, 5, INVITE_CODE, NOW);
    }

    private static List<CartProductInfo> catalog(Collection<Long> inventoryIds) {
        return List.of(UNIFORM_M, UNIFORM_L, BALL).stream()
                .filter(product -> inventoryIds.contains(product.inventoryId()))
                .toList();
    }

    private static CartProductInfo product(Long inventoryId, Long productId, Long sellerMemberId, String optionName) {
        return new CartProductInfo(inventoryId, productId, sellerMemberId, "상품" + productId, optionName,
                10000, null, true);
    }
}
