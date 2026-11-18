package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostComment;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSeller;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostSellerRepository;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.WithdrawnMemberCleanup;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostCommentHistoryRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostCommentRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostMemberClient;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductOptionRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.WithdrawnMemberCleanupRepository;
import com.cartmate.cartmate.global.http.InternalApiClient.InternalServerUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 탈퇴 회원 정리 한 번 실행: 마지막 성공 시각부터 받아 삭제 처리하고, 성공했을 때만 시각을 갱신한다.
 * 회원 서버 API는 아직 없어 클라이언트를 대역으로 바꾼다.
 */
@SpringBootTest(properties = "cartmate.scheduling.enabled=false")
@ActiveProfiles("test")
class SalesPostWithdrawnCleanupTest {

    private static final Long WITHDRAWN_MEMBER_ID = 100L;
    private static final Long OTHER_MEMBER_ID = 200L;
    private static final Long SELLER_ID = 7L;          // 회원 100의 판매자 ID
    private static final Long OTHER_SELLER_ID = 8L;    // 회원 200의 판매자 ID
    private static final LocalDateTime DAY1 = LocalDateTime.of(2026, 11, 19, 0, 0);
    private static final LocalDateTime DAY2 = LocalDateTime.of(2026, 11, 20, 0, 0);
    private static final LocalDateTime DAY3 = LocalDateTime.of(2026, 11, 21, 0, 0);

    @Autowired
    SalesPostFacade salesPostFacade;
    @Autowired
    WithdrawnMemberCleanupRepository withdrawnMemberCleanupRepository;
    @Autowired
    SalesPostRepository salesPostRepository;
    @Autowired
    SalesPostCommentRepository salesPostCommentRepository;
    @Autowired
    SalesPostCommentHistoryRepository salesPostCommentHistoryRepository;
    @Autowired
    SalesPostProductRepository salesPostProductRepository;
    @Autowired
    SalesPostProductImageRepository salesPostProductImageRepository;
    @Autowired
    SalesPostProductOptionRepository salesPostProductOptionRepository;

    @Autowired
    SalesPostSellerRepository salesPostSellerRepository;

    @MockitoBean
    SalesPostMemberClient salesPostMemberClient;

    Long postId;

    @BeforeEach
    void setUp() {
        salesPostSellerRepository.deleteAll();
        withdrawnMemberCleanupRepository.deleteAll();
        salesPostCommentHistoryRepository.deleteAll();
        salesPostCommentRepository.deleteAll();
        salesPostProductImageRepository.deleteAll();
        salesPostProductOptionRepository.deleteAll();
        salesPostProductRepository.deleteAll();
        salesPostRepository.deleteAll();

        // 댓글이 달리는 판매글. 탈퇴 판매자(7)와 관계없는 판매자(9)의 글
        postId = salesPostRepository.save(new SalesPost(9L, "판매글", "내용", SalesPostSaleStatus.ON_SALE, 0L,
                SalesPostUpdateStatus.NEW, LocalDateTime.now())).getId();
    }

    @Test
    @DisplayName("첫 실행은 처음부터(from 없이) 받아 삭제 처리하고, 마지막 성공 시각을 이번 실행 시각으로 저장한다")
    void firstRunFromStart() {
        SalesPostComment withdrawn = saveComment(WITHDRAWN_MEMBER_ID);
        SalesPostComment other = saveComment(OTHER_MEMBER_ID);
        given(salesPostMemberClient.findWithdrawnMemberIds(null, DAY1)).willReturn(List.of(WITHDRAWN_MEMBER_ID));

        SalesPostWithdrawnCleanupUseCase.Result result = salesPostFacade.cleanupWithdrawnMembers(DAY1);

        assertThat(result.from()).isNull();
        assertThat(result.to()).isEqualTo(DAY1);
        assertThat(result.withdrawnMembers()).isEqualTo(1);
        assertThat(result.deletedComments()).isEqualTo(1);
        assertThat(isDeleted(withdrawn)).isTrue();
        assertThat(isDeleted(other)).isFalse();
        assertThat(lastSucceededAt()).isEqualTo(DAY1);
        assertThat(withdrawnMemberCleanupRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("다음 실행은 지난 성공 시각부터 받고, 테이블은 계속 한 줄이다")
    void nextRunFromLastSuccess() {
        given(salesPostMemberClient.findWithdrawnMemberIds(null, DAY1)).willReturn(List.of());
        salesPostFacade.cleanupWithdrawnMembers(DAY1);
        SalesPostComment withdrawn = saveComment(WITHDRAWN_MEMBER_ID);
        given(salesPostMemberClient.findWithdrawnMemberIds(DAY1, DAY2)).willReturn(List.of(WITHDRAWN_MEMBER_ID));

        SalesPostWithdrawnCleanupUseCase.Result result = salesPostFacade.cleanupWithdrawnMembers(DAY2);

        assertThat(result.from()).isEqualTo(DAY1);
        assertThat(isDeleted(withdrawn)).isTrue();
        assertThat(lastSucceededAt()).isEqualTo(DAY2);
        assertThat(withdrawnMemberCleanupRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("회원 서버가 응답하지 않으면 삭제도 시각 갱신도 하지 않고, 다음 실행이 그 기간까지 함께 받는다")
    void failureKeepsLastSuccess() {
        given(salesPostMemberClient.findWithdrawnMemberIds(null, DAY1)).willReturn(List.of());
        salesPostFacade.cleanupWithdrawnMembers(DAY1);
        given(salesPostMemberClient.findWithdrawnMemberIds(DAY1, DAY2))
                .willThrow(new InternalServerUnavailableException("MEMBER", "회원", null));

        assertThatThrownBy(() -> salesPostFacade.cleanupWithdrawnMembers(DAY2))
                .isInstanceOf(InternalServerUnavailableException.class);
        assertThat(lastSucceededAt()).isEqualTo(DAY1);

        SalesPostComment withdrawn = saveComment(WITHDRAWN_MEMBER_ID);
        given(salesPostMemberClient.findWithdrawnMemberIds(DAY1, DAY3)).willReturn(List.of(WITHDRAWN_MEMBER_ID));

        SalesPostWithdrawnCleanupUseCase.Result result = salesPostFacade.cleanupWithdrawnMembers(DAY3);

        assertThat(result.from()).isEqualTo(DAY1);
        assertThat(isDeleted(withdrawn)).isTrue();
        assertThat(lastSucceededAt()).isEqualTo(DAY3);
    }

    @Test
    @DisplayName("받은 탈퇴 회원이 없어도 성공으로 보고 시각을 갱신한다")
    void noWithdrawnMembersStillSucceeds() {
        given(salesPostMemberClient.findWithdrawnMemberIds(null, DAY1)).willReturn(List.of());

        SalesPostWithdrawnCleanupUseCase.Result result = salesPostFacade.cleanupWithdrawnMembers(DAY1);

        assertThat(result.withdrawnMembers()).isZero();
        assertThat(result.deletedComments()).isZero();
        assertThat(result.deletedReviews()).isZero();
        assertThat(lastSucceededAt()).isEqualTo(DAY1);
    }

    @Test
    @DisplayName("탈퇴 완료 회원이 판매자면 그 판매자의 판매글(판매 중 · 판매 중지 · 판매 종료)을 삭제 중으로 바꾸고, 다른 판매자 글과 이미 삭제된 글은 그대로다")
    void deletesPostsOfWithdrawnSeller() {
        saveSeller(SELLER_ID, WITHDRAWN_MEMBER_ID);
        saveSeller(OTHER_SELLER_ID, OTHER_MEMBER_ID);
        Long onSale = savePost(SELLER_ID, SalesPostSaleStatus.ON_SALE, SalesPostUpdateStatus.NEW);
        Long paused = savePost(SELLER_ID, SalesPostSaleStatus.PAUSED, SalesPostUpdateStatus.NEW);
        Long closed = savePost(SELLER_ID, SalesPostSaleStatus.CLOSED, SalesPostUpdateStatus.NEW);
        Long alreadyDeleted = savePost(SELLER_ID, SalesPostSaleStatus.DELETED, SalesPostUpdateStatus.NEW);
        Long othersPost = savePost(OTHER_SELLER_ID, SalesPostSaleStatus.ON_SALE, SalesPostUpdateStatus.NEW);
        given(salesPostMemberClient.findWithdrawnMemberIds(null, DAY1)).willReturn(List.of(WITHDRAWN_MEMBER_ID));

        SalesPostWithdrawnCleanupUseCase.Result result = salesPostFacade.cleanupWithdrawnMembers(DAY1);

        assertThat(result.deletedPosts()).isEqualTo(3);
        assertThat(statusOf(onSale)).isEqualTo(SalesPostSaleStatus.DELETING);
        assertThat(statusOf(paused)).isEqualTo(SalesPostSaleStatus.DELETING);
        assertThat(statusOf(closed)).isEqualTo(SalesPostSaleStatus.DELETING);
        assertThat(statusOf(alreadyDeleted)).isEqualTo(SalesPostSaleStatus.DELETED);
        assertThat(statusOf(othersPost)).isEqualTo(SalesPostSaleStatus.ON_SALE);
        assertThat(lastSucceededAt()).isEqualTo(DAY1);
    }

    @Test
    @DisplayName("판매자가 아닌 탈퇴 회원이면 판매글은 건드리지 않는다")
    void nonSellerDoesNotTouchPosts() {
        given(salesPostMemberClient.findWithdrawnMemberIds(null, DAY1)).willReturn(List.of(WITHDRAWN_MEMBER_ID));

        SalesPostWithdrawnCleanupUseCase.Result result = salesPostFacade.cleanupWithdrawnMembers(DAY1);

        assertThat(result.deletedPosts()).isZero();
        assertThat(statusOf(postId)).isEqualTo(SalesPostSaleStatus.ON_SALE);
    }

    @Test
    @DisplayName("판매글 하나라도 삭제를 시작하지 못하면(수정 중) 댓글 · 리뷰 삭제와 시각 저장까지 전체가 롤백된다")
    void rollsBackWhenPostDeleteBlocked() {
        saveSeller(SELLER_ID, WITHDRAWN_MEMBER_ID);
        Long deletable = savePost(SELLER_ID, SalesPostSaleStatus.ON_SALE, SalesPostUpdateStatus.NEW);
        savePost(SELLER_ID, SalesPostSaleStatus.ON_SALE, SalesPostUpdateStatus.PENDING);   // 수정 중이라 삭제 불가
        SalesPostComment comment = saveComment(WITHDRAWN_MEMBER_ID);
        given(salesPostMemberClient.findWithdrawnMemberIds(null, DAY1)).willReturn(List.of(WITHDRAWN_MEMBER_ID));

        assertThatThrownBy(() -> salesPostFacade.cleanupWithdrawnMembers(DAY1)).isInstanceOf(RuntimeException.class);

        assertThat(statusOf(deletable)).isEqualTo(SalesPostSaleStatus.ON_SALE);
        assertThat(isDeleted(comment)).isFalse();
        assertThat(withdrawnMemberCleanupRepository.findById(WithdrawnMemberCleanup.SINGLE_ROW_ID)).isEmpty();
    }

    private void saveSeller(Long sellerId, Long memberId) {
        salesPostSellerRepository.save(new SalesPostSeller(sellerId, memberId, "INDIVIDUAL", null, null,
                "ACTIVE", LocalDateTime.now(), LocalDateTime.now()));
    }

    private Long savePost(Long sellerId, SalesPostSaleStatus status, SalesPostUpdateStatus updateStatus) {
        return salesPostRepository.save(new SalesPost(sellerId, "판매글", "내용", status, 0L,
                updateStatus, LocalDateTime.now())).getId();
    }

    private SalesPostSaleStatus statusOf(Long id) {
        return salesPostRepository.findById(id).orElseThrow().getStatus();
    }

    private SalesPostComment saveComment(Long memberId) {
        return salesPostCommentRepository.save(SalesPostComment.root(postId, memberId, "회원" + memberId, "문의", false));
    }

    private boolean isDeleted(SalesPostComment comment) {
        return salesPostCommentRepository.findById(comment.getId()).orElseThrow().isDeleted();
    }

    private LocalDateTime lastSucceededAt() {
        return withdrawnMemberCleanupRepository.findById(WithdrawnMemberCleanup.SINGLE_ROW_ID).orElseThrow()
                .getLastSucceededAt();
    }
}
