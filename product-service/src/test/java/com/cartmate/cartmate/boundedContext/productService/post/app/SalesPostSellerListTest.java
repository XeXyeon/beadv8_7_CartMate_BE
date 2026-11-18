package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectSalesPostRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectSalesPostResponse;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductOptionRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.global.page.PageResponse;
import com.cartmate.cartmate.support.ProductServiceTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 판매자 본인의 판매글 목록(판매글 관리 화면): 삭제된 글만 빼고 판매 중지 · 운영 중지 · 등록 중까지 모두, 최신순.
 * 수정 · 판매 중지 버튼을 판단할 수 있게 판매 상태 · 수정 진행 상태를 함께 준다.
 */
class SalesPostSellerListTest extends ProductServiceTestSupport {

    private static final Long SELLER_ID = 7L;
    private static final Long OTHER_SELLER_ID = 8L;

    @Autowired
    SalesPostFacade salesPostFacade;
    @Autowired
    SalesPostRepository salesPostRepository;
    @Autowired
    SalesPostProductRepository salesPostProductRepository;
    @Autowired
    SalesPostProductImageRepository salesPostProductImageRepository;
    @Autowired
    SalesPostProductOptionRepository salesPostProductOptionRepository;

    Long onSaleId;
    Long pausedId;
    Long suspendedId;
    Long updatingId;

    @BeforeEach
    void setUp() {
        salesPostProductImageRepository.deleteAll();
        salesPostProductOptionRepository.deleteAll();
        salesPostProductRepository.deleteAll();
        salesPostRepository.deleteAll();

        onSaleId = save(SELLER_ID, "판매 중", SalesPostSaleStatus.ON_SALE, SalesPostUpdateStatus.NEW);
        pausedId = save(SELLER_ID, "판매 중지", SalesPostSaleStatus.PAUSED, SalesPostUpdateStatus.NEW);
        suspendedId = save(SELLER_ID, "운영 중지", SalesPostSaleStatus.SUSPENDED, SalesPostUpdateStatus.NEW);
        save(SELLER_ID, "삭제됨", SalesPostSaleStatus.DELETED, SalesPostUpdateStatus.NEW);
        updatingId = save(SELLER_ID, "수정 중", SalesPostSaleStatus.ON_SALE, SalesPostUpdateStatus.PENDING);
        save(OTHER_SELLER_ID, "다른 판매자", SalesPostSaleStatus.ON_SALE, SalesPostUpdateStatus.NEW);
    }

    @Test
    @DisplayName("내 판매글만 최신순으로, 삭제된 글만 빼고 판매 중지 · 운영 중지까지 판매 상태 · 수정 진행 상태와 함께 나온다")
    void listsOwnPostsExceptDeleted() {
        PageResponse<SelectSalesPostResponse> page = salesPostFacade
                .selectSalesPostsBySeller(SELLER_ID, PageRequest.of(0, 10)).getData();

        assertThat(page.content())
                .extracting(SelectSalesPostResponse::getId, SelectSalesPostResponse::getSellerId,
                        SelectSalesPostResponse::getStatus, SelectSalesPostResponse::getUpdateStatus)
                .containsExactly(
                        tuple(updatingId, SELLER_ID, SalesPostSaleStatus.ON_SALE, SalesPostUpdateStatus.PENDING),
                        tuple(suspendedId, SELLER_ID, SalesPostSaleStatus.SUSPENDED, SalesPostUpdateStatus.NEW),
                        tuple(pausedId, SELLER_ID, SalesPostSaleStatus.PAUSED, SalesPostUpdateStatus.NEW),
                        tuple(onSaleId, SELLER_ID, SalesPostSaleStatus.ON_SALE, SalesPostUpdateStatus.NEW));
        assertThat(page.totalElements()).isEqualTo(4);
    }

    @Test
    @DisplayName("페이지 단위로 나온다(전체 개수는 삭제된 글을 뺀 수)")
    void paginates() {
        PageResponse<SelectSalesPostResponse> second = salesPostFacade
                .selectSalesPostsBySeller(SELLER_ID, PageRequest.of(1, 3)).getData();

        assertThat(second.content()).extracting(SelectSalesPostResponse::getId).containsExactly(onSaleId);
        assertThat(second.totalElements()).isEqualTo(4);
        assertThat(second.totalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("판매글이 없는 판매자는 빈 목록")
    void emptyForSellerWithoutPosts() {
        PageResponse<SelectSalesPostResponse> page = salesPostFacade
                .selectSalesPostsBySeller(99L, PageRequest.of(0, 10)).getData();

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    // 공개 목록(홈 · 검색)과의 차이: 공개 목록은 판매 중인 글만, 판매자 목록은 삭제된 글만 뺀다
    @Test
    @DisplayName("공개 목록에는 판매 중인 글만 나온다 (판매 중지 · 운영 중지 · 판매 종료 · 등록 중 · 삭제 중 · 삭제됨은 빠진다)")
    void publicListShowsOnlyOnSale() {
        save(SELLER_ID, "판매 종료", SalesPostSaleStatus.CLOSED, SalesPostUpdateStatus.NEW);
        save(SELLER_ID, "등록 중", SalesPostSaleStatus.PENDING, SalesPostUpdateStatus.NEW);
        save(SELLER_ID, "삭제 중", SalesPostSaleStatus.DELETING, SalesPostUpdateStatus.NEW);

        PageResponse<SelectSalesPostResponse> page = salesPostFacade
                .selectSalesPostAll(new SelectSalesPostRequest(), PageRequest.of(0, 20)).getData();

        assertThat(page.content()).extracting(SelectSalesPostResponse::getStatus)
                .containsOnly(SalesPostSaleStatus.ON_SALE);
        assertThat(page.content()).extracting(SelectSalesPostResponse::getTitle)
                .containsExactlyInAnyOrder("판매 중", "수정 중", "다른 판매자");
        assertThat(page.totalElements()).isEqualTo(3);
    }

    private Long save(Long sellerId, String title, SalesPostSaleStatus status, SalesPostUpdateStatus updateStatus) {
        return salesPostRepository.save(new SalesPost(sellerId, title, "내용", status, 0L, updateStatus,
                LocalDateTime.now())).getId();
    }
}
