package com.cartmate.cartmate.boundedContext.orderService.order.app.personal;

import com.cartmate.cartmate.boundedContext.orderService.order.domain.buyercancel.BuyerCancelRequest;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.buyercancel.BuyerCancelRequest.Target;
import com.cartmate.cartmate.boundedContext.orderService.order.domain.personal.Order;
import com.cartmate.cartmate.boundedContext.orderService.order.dto.personal.OrderDetailResponse;
import com.cartmate.cartmate.boundedContext.orderService.order.dto.personal.OrderDetailResponse.ItemResponse;
import com.cartmate.cartmate.boundedContext.orderService.order.exception.OrderErrorCode;
import com.cartmate.cartmate.boundedContext.orderService.order.out.buyercancel.BuyerCancelRequestRepository;
import com.cartmate.cartmate.boundedContext.orderService.order.out.personal.OrderRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.cartmate.cartmate.boundedContext.orderService.order.support.OrderFixtures.ballLine;
import static com.cartmate.cartmate.boundedContext.orderService.order.support.OrderFixtures.personalOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class OrderQueryUseCaseTest {

    private static final Long ORDER_ID = 100L;
    private static final Long BUYER = 1L;
    private static final Long OTHER = 2L;
    private static final Long APPLE = 501L;
    private static final Long MILK = 502L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 12, 0);

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private BuyerCancelRequestRepository buyerCancelRequestRepository;

    private OrderQueryUseCase queryUseCase;

    @BeforeEach
    void setUp() {
        queryUseCase = new OrderQueryUseCase(orderRepository, new OrderSupport(orderRepository), buyerCancelRequestRepository);
    }

    @Test
    @DisplayName("주문한 회원이 아니면 주문 상세를 볼 수 없다")
    void detailByNonBuyer() {
        Order order = personalOrder(BUYER, ballLine(null, 1), LocalDateTime.now());
        given(orderRepository.findWithItemsById(ORDER_ID)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> queryUseCase.getDetail(OTHER, ORDER_ID))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(OrderErrorCode.ORDER_BUYER_ONLY.getMsg());
    }

    @Test
    @DisplayName("처리 중인 구매자 취소 요청에 든 상품만 취소 처리 중으로 보여 준다")
    void detail_itemInPendingRequest_cancelInProgressTrue() {
        givenOrder();
        givenRequests(request(1L, APPLE));

        OrderDetailResponse detail = queryUseCase.getDetail(BUYER, ORDER_ID);

        assertThat(detail.items()).extracting(ItemResponse::cancelInProgress, ItemResponse::cancelRejectCode)
                .containsExactly(tuple(true, null), tuple(false, null));
    }

    @Test
    @DisplayName("가장 최근 요청에서 거절된 상품은 거절 코드를 보여 준다")
    void detail_itemRejected_showsRejectCode() {
        givenOrder();
        BuyerCancelRequest rejected = request(1L, APPLE);
        rejected.rejectSeller(7L, "409-X");
        rejected.complete();
        givenRequests(rejected);

        OrderDetailResponse detail = queryUseCase.getDetail(BUYER, ORDER_ID);

        assertThat(detail.items()).extracting(ItemResponse::cancelInProgress, ItemResponse::cancelRejectCode)
                .containsExactly(tuple(false, "409-X"), tuple(false, null));
    }

    @Test
    @DisplayName("거절된 뒤 다시 요청한 상품은 거절 코드 없이 취소 처리 중으로 보여 준다")
    void detail_itemRejectedThenRequestedAgain_noRejectCode() {
        givenOrder();
        BuyerCancelRequest rejected = request(1L, APPLE);
        rejected.rejectSeller(7L, "409-X");
        rejected.complete();
        givenRequests(request(2L, APPLE), rejected);

        OrderDetailResponse detail = queryUseCase.getDetail(BUYER, ORDER_ID);

        assertThat(detail.items().get(0).cancelInProgress()).isTrue();
        assertThat(detail.items().get(0).cancelRejectCode()).isNull();
    }

    // 사과(판매자 7), 우유(판매자 8)를 결제한 주문
    private void givenOrder() {
        Order order = personalOrder("20261116-DETAILCX", BUYER, List.of(
                new Order.OrderLine(10L, 11L, 7L, "사과", null, 10_000L, 1),
                new Order.OrderLine(30L, 31L, 8L, "우유", null, 3_000L, 1)), NOW);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        ReflectionTestUtils.setField(order.getItems().get(0), "id", APPLE);
        ReflectionTestUtils.setField(order.getItems().get(1), "id", MILK);
        order.markPaid(1L, NOW);
        given(orderRepository.findWithItemsById(ORDER_ID)).willReturn(Optional.of(order));
    }

    private static BuyerCancelRequest request(Long requestId, Long orderItemId) {
        BuyerCancelRequest request = BuyerCancelRequest.create(ORDER_ID, BUYER, List.of(new Target(orderItemId, 7L)), NOW);
        ReflectionTestUtils.setField(request, "id", requestId);
        return request;
    }

    private void givenRequests(BuyerCancelRequest... newestFirst) {
        given(buyerCancelRequestRepository.findWithItemsByOrderId(ORDER_ID)).willReturn(List.of(newestFirst));
    }
}
