package com.cartmate.cartmate.boundedContext.productService.product.app;

import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostCreateUseCase;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostRequest;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductOptionRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.product.domain.InventoryReservation;
import com.cartmate.cartmate.boundedContext.productService.product.domain.InventoryReservationStatus;
import com.cartmate.cartmate.boundedContext.productService.product.dto.CreateInventory;
import com.cartmate.cartmate.boundedContext.productService.product.dto.CreateProduct;
import com.cartmate.cartmate.boundedContext.productService.product.dto.CreateProductImage;
import com.cartmate.cartmate.boundedContext.productService.product.dto.CreateProductOption;
import com.cartmate.cartmate.boundedContext.productService.product.dto.CreateProductRequest;
import com.cartmate.cartmate.boundedContext.productService.product.out.FakeImageStorage;
import com.cartmate.cartmate.boundedContext.productService.product.out.InventoryRepository;
import com.cartmate.cartmate.boundedContext.productService.product.out.InventoryReservationRepository;
import com.cartmate.cartmate.boundedContext.productService.product.out.ProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.product.out.ProductOptionRepository;
import com.cartmate.cartmate.boundedContext.productService.product.out.ProductRepository;
import com.cartmate.cartmate.shared.delivery.DeliveryCancelReason;
import com.cartmate.cartmate.shared.order.OrderCancelReason;
import com.cartmate.cartmate.shared.order.OrderType;
import com.cartmate.cartmate.shared.order.SharedGroupOrderCancelReason;
import com.cartmate.cartmate.shared.order.event.OrderCanceledEvent;
import com.cartmate.cartmate.shared.order.event.SharedGroupOrderCanceledEvent;
import com.cartmate.cartmate.shared.order.event.SharedGroupOrderConfirmedEvent;
import com.cartmate.cartmate.shared.order.event.SharedGroupOrderSellerItemsCanceledEvent;
import com.cartmate.cartmate.shared.product.dto.InventoryReserveRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static com.cartmate.cartmate.support.SeedOptions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 공동 주문(SHARED_GROUP) 이벤트 → 재고: 공동 주문 확정 = 판매 확정, 공동 주문 취소 = 선점 해제(확정 전) · 재고 복구(확정 후),
 * 판매자 몫 취소 = 그 판매자 재고만 재고 복구. 공동 주문 id는 개인 주문 id와 겹칠 수 있어 주문 종류로 구분한다.
 * 이벤트는 인박스처럼 트랜잭션 안에서 발행한다.
 */
@SpringBootTest(properties = "cartmate.scheduling.enabled=false")
@ActiveProfiles("test")
class SharedGroupOrderInventoryTest {

    private static final Long SELLER_ID = 7L;
    private static final Long OTHER_SELLER_ID = 8L;
    private static final Long CREATOR_ID = 300L;
    // 개인 주문과 같은 id를 일부러 쓴다 (서로 다른 테이블이라 실제로 겹칠 수 있다)
    private static final Long ORDER_ID = 15L;

    @Autowired
    ProductFacade productFacade;
    @Autowired
    ApplicationEventPublisher eventPublisher;
    @Autowired
    TransactionTemplate transactionTemplate;
    @Autowired
    ProductRepository productRepository;
    @Autowired
    ProductImageRepository productImageRepository;
    @Autowired
    ProductOptionRepository productOptionRepository;
    @Autowired
    InventoryRepository inventoryRepository;
    @Autowired
    InventoryReservationRepository inventoryReservationRepository;
    @Autowired
    FakeImageStorage storage;

    @Autowired
    SalesPostCreateUseCase salesPostCreateUseCase;
    @Autowired
    SalesPostRepository salesPostRepository;
    @Autowired
    SalesPostProductRepository salesPostProductRepository;
    @Autowired
    SalesPostProductImageRepository salesPostProductImageRepository;
    @Autowired
    SalesPostProductOptionRepository salesPostProductOptionRepository;

    Long blackId;  // 판매자 7, 재고 10개
    Long ballId;   // 판매자 8, 재고 20개

    @BeforeEach
    void setUp() {
        inventoryReservationRepository.deleteAll();
        salesPostProductImageRepository.deleteAll();
        salesPostProductOptionRepository.deleteAll();
        salesPostProductRepository.deleteAll();
        salesPostRepository.deleteAll();
        productImageRepository.deleteAll();
        productOptionRepository.deleteAll();
        inventoryRepository.deleteAll();
        productRepository.deleteAll();
        storage.reset();

        register(SELLER_ID, "유니폼", new CreateInventory(List.of(
                new CreateProductOption(CLOTHING_SIZE, SIZE_L), new CreateProductOption(COLOR, BLACK)), 0L, 10));
        register(OTHER_SELLER_ID, "축구공", new CreateInventory(List.of(
                new CreateProductOption(4L, 37L)), 0L, 20));   // 공 사이즈 5호
        blackId = inventoryIdOf("L / 블랙");
        ballId = inventoryIdOf("5호");

        // 공동 주문 15번: 유니폼 2 + 축구공 3 선점
        reserve(OrderType.SHARED_GROUP, 2, 3);
    }

    @Test
    @DisplayName("공동 주문으로 선점하면 선점 수량이 늘고 선점 기록이 주문 종류 SHARED_GROUP으로 저장된다")
    void reservesAsSharedGroup() {
        assertThat(stockOf(blackId)).isEqualTo(10);
        assertThat(reservedOf(blackId)).isEqualTo(2);
        assertThat(reservedOf(ballId)).isEqualTo(3);
        assertThat(statuses(OrderType.SHARED_GROUP)).containsOnly(InventoryReservationStatus.RESERVED);
    }

    @Test
    @DisplayName("공동 주문 확정 → 판매 확정: 재고와 선점에서 함께 빠진다. 두 번 와도 한 번만")
    void confirmedConfirms() {
        publish(confirmed());
        publish(confirmed());

        assertThat(stockOf(blackId)).isEqualTo(8);
        assertThat(reservedOf(blackId)).isZero();
        assertThat(stockOf(ballId)).isEqualTo(17);
        assertThat(statuses(OrderType.SHARED_GROUP)).containsOnly(InventoryReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("확정 전 공동 주문 취소(기한 만료 등) → 선점 해제: 재고는 그대로, 선점만 0")
    void canceledBeforeConfirmReleases() {
        publish(canceled(SharedGroupOrderCancelReason.DEADLINE_EXPIRED));

        assertThat(stockOf(blackId)).isEqualTo(10);
        assertThat(reservedOf(blackId)).isZero();
        assertThat(statuses(OrderType.SHARED_GROUP)).containsOnly(InventoryReservationStatus.RELEASED);
    }

    @Test
    @DisplayName("확정 후 공동 주문 취소 → 재고 복구: 판매 확정으로 빠진 재고가 돌아온다. 두 번 와도 한 번만")
    void canceledAfterConfirmRestores() {
        publish(confirmed());
        publish(canceled(SharedGroupOrderCancelReason.SALES_STOPPED));
        publish(canceled(SharedGroupOrderCancelReason.SALES_STOPPED));

        assertThat(stockOf(blackId)).isEqualTo(10);
        assertThat(stockOf(ballId)).isEqualTo(20);
        assertThat(reservedOf(blackId)).isZero();
        assertThat(statuses(OrderType.SHARED_GROUP)).containsOnly(InventoryReservationStatus.RESTORED);
    }

    @Test
    @DisplayName("판매자 몫 취소 → 그 판매자 재고만 재고 복구, 다른 판매자 재고는 판매 확정 그대로")
    void sellerItemsCanceledRestoresOnlyThatSeller() {
        publish(confirmed());
        publish(new SharedGroupOrderSellerItemsCanceledEvent(ORDER_ID, OTHER_SELLER_ID, 75000L, LocalDateTime.now(),
                DeliveryCancelReason.SALES_STOPPED, 1L));

        assertThat(stockOf(ballId)).isEqualTo(20);
        assertThat(stockOf(blackId)).isEqualTo(8);
        assertThat(inventoryReservationRepository.findByOrderTypeAndOrderIdOrderByInventoryIdAsc(OrderType.SHARED_GROUP, ORDER_ID))
                .extracting(InventoryReservation::getInventoryId, InventoryReservation::getStatus)
                .containsExactlyInAnyOrder(
                        tuple(blackId, InventoryReservationStatus.CONFIRMED),
                        tuple(ballId, InventoryReservationStatus.RESTORED));
    }

    @Test
    @DisplayName("개인 주문과 공동 주문의 id가 같아도 한쪽의 판매 확정 · 취소가 다른 쪽 선점에 영향을 주지 않는다")
    void sameIdDoesNotMixWithPersonalOrder() {
        reserve(OrderType.PERSONAL, 1, 1);   // 개인 주문 15번: 유니폼 1 + 축구공 1
        assertThat(reservedOf(blackId)).isEqualTo(3);

        publish(confirmed());   // 공동 주문 15번만 판매 확정
        assertThat(statuses(OrderType.SHARED_GROUP)).containsOnly(InventoryReservationStatus.CONFIRMED);
        assertThat(statuses(OrderType.PERSONAL)).containsOnly(InventoryReservationStatus.RESERVED);
        assertThat(stockOf(blackId)).isEqualTo(8);
        assertThat(reservedOf(blackId)).isEqualTo(1);

        // 개인 주문 15번 취소는 공동 주문 15번에 영향 없음
        publish(new OrderCanceledEvent(ORDER_ID, 1L, OrderType.PERSONAL, OrderCancelReason.PAYMENT_EXPIRED,
                false, LocalDateTime.now(), null));
        assertThat(statuses(OrderType.PERSONAL)).containsOnly(InventoryReservationStatus.RELEASED);
        assertThat(statuses(OrderType.SHARED_GROUP)).containsOnly(InventoryReservationStatus.CONFIRMED);
        assertThat(stockOf(blackId)).isEqualTo(8);
        assertThat(reservedOf(blackId)).isZero();
    }

    @Test
    @DisplayName("선점 기록이 없는 공동 주문의 이벤트는 아무것도 바꾸지 않는다 (이 처리가 생기기 전 공동 주문 등)")
    void eventWithoutReservationDoesNothing() {
        publish(new SharedGroupOrderConfirmedEvent(99L, 5L, CREATOR_ID, LocalDateTime.now(), null, List.of()));
        publish(new SharedGroupOrderCanceledEvent(98L, 5L, SharedGroupOrderCancelReason.DEADLINE_EXPIRED,
                LocalDateTime.now(), List.of(), CREATOR_ID, List.of()));

        assertThat(stockOf(blackId)).isEqualTo(10);
        assertThat(reservedOf(blackId)).isEqualTo(2);
    }

    private void reserve(OrderType orderType, int blackQuantity, int ballQuantity) {
        productFacade.reserveInventories(new InventoryReserveRequest(orderType, ORDER_ID, List.of(
                new InventoryReserveRequest.Item(blackId, blackQuantity),
                new InventoryReserveRequest.Item(ballId, ballQuantity))));
    }

    // 인박스처럼 트랜잭션 안에서 발행한다(리스너는 커밋 직전에 실행)
    private void publish(Object event) {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));
    }

    private SharedGroupOrderConfirmedEvent confirmed() {
        return new SharedGroupOrderConfirmedEvent(ORDER_ID, 5L, CREATOR_ID, LocalDateTime.now(), null, List.of());
    }

    private SharedGroupOrderCanceledEvent canceled(SharedGroupOrderCancelReason reason) {
        return new SharedGroupOrderCanceledEvent(ORDER_ID, 5L, reason, LocalDateTime.now(), List.of(), CREATOR_ID, List.of());
    }

    private void register(Long sellerId, String name, CreateInventory inventory) {
        SalesPost post = salesPostCreateUseCase.saveSalesPost(
                new CreateSalesPostRequest(null, sellerId, name + " 팝니다", "새 상품", List.of()));
        CreateProduct product = new CreateProduct(null, sellerId, post.getId(), 1L, name, "설명", 25000L,
                List.of(new CreateProductImage(null, sellerId, null, null, 0, null)), List.of(inventory));
        productFacade.registerProduct(new CreateProductRequest(post.getId(), List.of(product)), List.of(TestImageFiles.jpeg()));
    }

    private List<InventoryReservationStatus> statuses(OrderType orderType) {
        return inventoryReservationRepository.findByOrderTypeAndOrderIdOrderByInventoryIdAsc(orderType, ORDER_ID).stream()
                .map(InventoryReservation::getStatus)
                .toList();
    }

    private int reservedOf(Long inventoryId) {
        return inventoryRepository.findById(inventoryId).orElseThrow().getReservedQuantity();
    }

    private int stockOf(Long inventoryId) {
        return inventoryRepository.findById(inventoryId).orElseThrow().getStockQuantity();
    }

    private Long inventoryIdOf(String skuName) {
        return inventoryRepository.findAll().stream()
                .filter(i -> i.getSkuName().equals(skuName))
                .findFirst()
                .orElseThrow()
                .getId();
    }
}
