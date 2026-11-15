package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProduct;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostProductStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostPurchase;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostReview;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostReviewRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SalesPostReviewResponse;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectSalesPostResponse;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostMemberClient;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductOptionRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostPurchaseRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostReviewHistoryRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostReviewRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.page.PageResponse;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * 판매글 리뷰 목록(최신순 10개씩, 삭제된 리뷰 제외, isMine, 판매자 답글)과
 * 판매글 응답의 리뷰 요약(리뷰 수 · 평균 별점 소수 첫째 자리).
 * 삭제 · 판매자 답글 API는 6 · 7단계에서 만들어서, 여기서는 그 상태를 테스트 데이터로 직접 만든다.
 */
@SpringBootTest(properties = "cartmate.scheduling.enabled=false")
@ActiveProfiles("test")
class SalesPostReviewSelectTest {

    private static final Long SELLER_ID = 7L;
    private static final Long BUYER_ID = 100L;
    private static final Long OTHER_BUYER_ID = 200L;

    private static final AtomicLong ORDER_ITEM_SEQ = new AtomicLong(2000);
    private static final AtomicLong PRODUCT_SEQ = new AtomicLong(19000);

    @Autowired
    SalesPostFacade salesPostFacade;
    @Autowired
    SalesPostSelectUseCase salesPostSelectUseCase;
    @Autowired
    SalesPostRepository salesPostRepository;
    @Autowired
    SalesPostProductRepository salesPostProductRepository;
    @Autowired
    SalesPostProductImageRepository salesPostProductImageRepository;
    @Autowired
    SalesPostProductOptionRepository salesPostProductOptionRepository;
    @Autowired
    SalesPostPurchaseRepository salesPostPurchaseRepository;
    @Autowired
    SalesPostReviewRepository salesPostReviewRepository;
    @Autowired
    SalesPostReviewHistoryRepository salesPostReviewHistoryRepository;

    @MockitoBean
    SalesPostMemberClient salesPostMemberClient;

    Long postId;
    Long productId;

    @BeforeEach
    void setUp() {
        salesPostReviewHistoryRepository.deleteAll();
        salesPostReviewRepository.deleteAll();
        salesPostPurchaseRepository.deleteAll();
        salesPostProductImageRepository.deleteAll();
        salesPostProductOptionRepository.deleteAll();
        salesPostProductRepository.deleteAll();
        salesPostRepository.deleteAll();

        given(salesPostMemberClient.findNickname(anyLong())).willAnswer(inv -> "회원" + inv.getArgument(0));
        postId = savePost(SalesPostSaleStatus.ON_SALE);
        productId = saveProduct(postId);
    }

    @Test
    @DisplayName("리뷰는 최신순으로 10개씩 나뉘고, 전체 개수 · 페이지 수가 함께 온다")
    void paginatesNewestFirst() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ids.add(write(BUYER_ID, 5));
        }

        PageResponse<SalesPostReviewResponse> first = salesPostFacade.selectReviews(postId, 0, null);
        PageResponse<SalesPostReviewResponse> second = salesPostFacade.selectReviews(postId, 1, null);

        assertThat(first.content()).extracting(SalesPostReviewResponse::reviewId)
                .containsExactlyElementsOf(ids.reversed().subList(0, 10));
        assertThat(second.content()).extracting(SalesPostReviewResponse::reviewId)
                .containsExactly(ids.get(1), ids.get(0));
        assertThat(first.totalElements()).isEqualTo(12);
        assertThat(first.totalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("리뷰에는 닉네임 · 상품명 · 선택된 옵션 · 별점 · 내용이 오고, 판매자 답글이 있으면 함께 온다")
    void containsReviewAndSellerReply() {
        Long withoutReply = write(BUYER_ID, 4);
        Long withReply = write(OTHER_BUYER_ID, 2);
        SalesPostReview review = salesPostReviewRepository.findById(withReply).orElseThrow();
        LocalDateTime repliedAt = LocalDateTime.of(2026, 11, 10, 9, 0);
        ReflectionTestUtils.setField(review, "sellerReplyContent", "불편을 드려 죄송합니다.");
        ReflectionTestUtils.setField(review, "sellerRepliedAt", repliedAt);
        salesPostReviewRepository.save(review);

        List<SalesPostReviewResponse> list = salesPostFacade.selectReviews(postId, 0, null).content();

        SalesPostReviewResponse replied = list.get(0);
        assertThat(replied.reviewId()).isEqualTo(withReply);
        assertThat(replied.writerNickname()).isEqualTo("회원200");
        assertThat(replied.productName()).isEqualTo("반팔 티셔츠");
        assertThat(replied.optionName()).isEqualTo("블랙 / L");
        assertThat(replied.rating()).isEqualTo(2);
        assertThat(replied.sellerReply()).isNotNull();
        assertThat(replied.sellerReply().content()).isEqualTo("불편을 드려 죄송합니다.");
        assertThat(replied.sellerReply().repliedAt()).isEqualTo(repliedAt);
        assertThat(replied.sellerReply().isEdited()).isFalse();

        assertThat(list.get(1).reviewId()).isEqualTo(withoutReply);
        assertThat(list.get(1).sellerReply()).isNull();
    }

    @Test
    @DisplayName("삭제된 리뷰는 목록과 전체 개수에서 빠진다")
    void excludesDeleted() {
        Long alive = write(BUYER_ID, 5);
        Long deleted = write(OTHER_BUYER_ID, 1);
        SalesPostReview review = salesPostReviewRepository.findById(deleted).orElseThrow();
        ReflectionTestUtils.setField(review, "isDeleted", true);
        salesPostReviewRepository.save(review);

        PageResponse<SalesPostReviewResponse> page = salesPostFacade.selectReviews(postId, 0, null);

        assertThat(page.content()).extracting(SalesPostReviewResponse::reviewId).containsExactly(alive);
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("내가 쓴 리뷰면 isMine이고, 로그인하지 않으면 모두 아니다")
    void marksMine() {
        Long mine = write(BUYER_ID, 5);
        Long others = write(OTHER_BUYER_ID, 3);

        assertThat(salesPostFacade.selectReviews(postId, 0, BUYER_ID).content())
                .extracting(SalesPostReviewResponse::reviewId, SalesPostReviewResponse::isMine)
                .containsExactly(tuple(others, false),
                        tuple(mine, true));
        assertThat(salesPostFacade.selectReviews(postId, 0, null).content())
                .extracting(SalesPostReviewResponse::isMine).containsOnly(false);
    }

    @Test
    @DisplayName("판매 종료된 판매글의 리뷰는 볼 수 있고, 운영 중지된 판매글은 없는 글로 응답한다")
    void visibilityByPostStatus() {
        Long closedId = savePost(SalesPostSaleStatus.CLOSED);
        Long suspendedId = savePost(SalesPostSaleStatus.SUSPENDED);

        assertThat(salesPostFacade.selectReviews(closedId, 0, null).content()).isEmpty();
        assertThatThrownBy(() -> salesPostFacade.selectReviews(suspendedId, 0, null))
                .isInstanceOf(ServiceException.class)
                .extracting("resultCode")
                .isEqualTo(SalesPostErrorCode.POST_NOT_FOUND.resultCode());
    }

    @Test
    @DisplayName("판매글 응답에 리뷰 수와 평균 별점(소수 첫째 자리 반올림)이 온다")
    void postResponseHasReviewSummary() {
        write(BUYER_ID, 5);
        write(BUYER_ID, 5);
        write(OTHER_BUYER_ID, 4);   // 14 / 3 = 4.666… → 4.7

        SelectSalesPostResponse detail = salesPostSelectUseCase.selectSalesPostDetail(postId);
        assertThat(detail.getReviewCount()).isEqualTo(3);
        assertThat(detail.getAverageRating()).isEqualTo(4.7);

        SelectSalesPostResponse inSellerList = salesPostSelectUseCase
                .selectSalesPostsBySeller(SELLER_ID, PageRequest.of(0, 10)).content().stream()
                .filter(p -> p.getId().equals(postId)).findFirst().orElseThrow();
        assertThat(inSellerList.getReviewCount()).isEqualTo(3);
        assertThat(inSellerList.getAverageRating()).isEqualTo(4.7);
    }

    @Test
    @DisplayName("리뷰가 없는 판매글의 평균 별점은 0이다")
    void noReviewsMeansZero() {
        SelectSalesPostResponse detail = salesPostSelectUseCase.selectSalesPostDetail(postId);

        assertThat(detail.getReviewCount()).isZero();
        assertThat(detail.getAverageRating()).isZero();
    }

    // 구매확정된 구매 기록을 만들고 리뷰를 쓴다
    private Long write(Long buyerId, int rating) {
        SalesPostPurchase purchase = SalesPostPurchase.purchased(PurchaseType.INDIVIDUAL_GROUP, 31L,
                ORDER_ITEM_SEQ.incrementAndGet(), buyerId, productId, productId * 10, SELLER_ID, "반팔 티셔츠", "블랙 / L");
        purchase.confirm(LocalDateTime.now().minusDays(1));
        Long purchaseId = salesPostPurchaseRepository.save(purchase).getId();
        return salesPostFacade.createReview(buyerId,
                new CreateSalesPostReviewRequest(purchaseId, rating, "사이즈가 딱 맞고 품질도 좋아요.")).reviewId();
    }

    private Long savePost(SalesPostSaleStatus status) {
        return salesPostRepository.save(new SalesPost(SELLER_ID, "판매글", "내용", status, 0L,
                SalesPostUpdateStatus.NEW, LocalDateTime.now())).getId();
    }

    private Long saveProduct(Long postId) {
        SalesPost post = salesPostRepository.findById(postId).orElseThrow();
        Long id = PRODUCT_SEQ.incrementAndGet();
        salesPostProductRepository.save(new SalesPostProduct(id, SELLER_ID, post, 1L, "반팔 티셔츠", "설명", 10000L,
                SalesPostProductStatus.ON_SALE, LocalDateTime.now(), LocalDateTime.now()));
        return id;
    }
}
