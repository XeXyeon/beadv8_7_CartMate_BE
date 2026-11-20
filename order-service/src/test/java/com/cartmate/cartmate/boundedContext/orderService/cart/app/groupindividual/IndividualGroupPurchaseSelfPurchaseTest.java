package com.cartmate.cartmate.boundedContext.orderService.cart.app.groupindividual;

import com.cartmate.cartmate.boundedContext.orderService.cart.app.InviteCodeGenerator;
import com.cartmate.cartmate.boundedContext.orderService.cart.domain.groupindividual.IndividualGroupPurchase;
import com.cartmate.cartmate.boundedContext.orderService.cart.dto.groupindividual.IndividualGroupPurchaseCreateRequest;
import com.cartmate.cartmate.boundedContext.orderService.cart.exception.CartErrorCode;
import com.cartmate.cartmate.boundedContext.orderService.cart.out.FakeProductClient;
import com.cartmate.cartmate.boundedContext.orderService.cart.out.groupindividual.IndividualGroupPurchaseOrderClient;
import com.cartmate.cartmate.boundedContext.orderService.cart.out.groupindividual.IndividualGroupPurchaseRepository;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.global.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static com.cartmate.cartmate.support.TestCatalog.SELLER_MEMBER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * 개인물품 공동구매에서 판매자의 자기 상품 구매 막기.
 * 판매자가 자기 상품으로 공동구매를 열면 주문할 때 가서야 막히므로, 열거나 상품을 더할 때 막는다.
 * 상품은 TestCatalog 기준으로 모두 SELLER_MEMBER_ID가 판다.
 */
@ExtendWith(MockitoExtension.class)
class IndividualGroupPurchaseSelfPurchaseTest {

    private static final Clock CLOCK = Clock.fixed(
            LocalDateTime.of(2026, 11, 20, 12, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    private static final Long BUYER = 1L;
    private static final Long UNIFORM = 1L;
    private static final Long BALL = 2L;

    @Mock
    private IndividualGroupPurchaseRepository repository;
    @Mock
    private IndividualGroupPurchaseOrderClient orderClient;
    @Mock
    private DomainEventPublisher eventPublisher;

    private IndividualGroupPurchaseCreateUseCase createUseCase;
    private IndividualGroupPurchaseManageUseCase manageUseCase;

    @BeforeEach
    void setUp() {
        IndividualGroupPurchaseSupport support = new IndividualGroupPurchaseSupport(new FakeProductClient());
        createUseCase = new IndividualGroupPurchaseCreateUseCase(
                repository, support, new InviteCodeGenerator(), eventPublisher, CLOCK);
        manageUseCase = new IndividualGroupPurchaseManageUseCase(
                repository, support, orderClient, eventPublisher, CLOCK);
    }

    @Test
    @DisplayName("판매자는 자기 상품이 들어간 공동구매를 열 수 없다")
    void sellerCannotOpenWithOwnProduct() {
        assertThatThrownBy(() -> createUseCase.create(SELLER_MEMBER_ID,
                new IndividualGroupPurchaseCreateRequest("축구 용품", List.of(UNIFORM))))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getResultCode())
                        .isEqualTo(CartErrorCode.CART_OWN_PRODUCT.resultCode()));
        then(repository).should(never()).save(any());
    }

    @Test
    @DisplayName("판매자가 아닌 회원은 같은 상품으로 공동구매를 열 수 있다")
    void buyerCanOpen() {
        given(repository.save(any(IndividualGroupPurchase.class))).willAnswer(invocation -> invocation.getArgument(0));

        createUseCase.create(BUYER, new IndividualGroupPurchaseCreateRequest("축구 용품", List.of(UNIFORM)));

        then(repository).should().save(any(IndividualGroupPurchase.class));
    }

    @Test
    @DisplayName("판매자는 연 공동구매에 자기 상품을 더할 수 없다")
    void sellerCannotAddOwnProduct() {
        assertThatThrownBy(() -> manageUseCase.addProduct(SELLER_MEMBER_ID, 10L, BALL))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getResultCode())
                        .isEqualTo(CartErrorCode.CART_OWN_PRODUCT.resultCode()));
        then(repository).should(never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("판매자가 아닌 개설자는 상품을 더할 수 있다")
    void buyerCanAddProduct() {
        IndividualGroupPurchase purchase = IndividualGroupPurchase.create(
                "축구 용품", BUYER, "abcdefghijkl", List.of(UNIFORM), LocalDateTime.now(CLOCK));
        given(repository.findByIdForUpdate(10L)).willReturn(Optional.of(purchase));

        manageUseCase.addProduct(BUYER, 10L, BALL);

        assertThat(purchase.productIds()).containsExactly(UNIFORM, BALL);
    }
}
