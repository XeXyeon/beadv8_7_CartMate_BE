package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostComment;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostPurchase;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostReview;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostCommentHistoryRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostCommentRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostMemberClient;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductOptionRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostPurchaseRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostReviewHistoryRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostReviewRepository;
import com.cartmate.cartmate.shared.delivery.PurchaseType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 탈퇴한 회원의 댓글 · 리뷰 작성자를 "탈퇴한 회원"으로 바꾸는 처리.
 *
 * <p>member-service에 탈퇴 완료 처리가 없고 탈퇴 이벤트가 서버 간으로 오지 않아, 아직 이벤트로 연결하지 않았다.
 * 그래서 이벤트 수신이 아니라 UseCase를 직접 불러 product-service 쪽 동작만 확인한다.
 */
@SpringBootTest(properties = "cartmate.scheduling.enabled=false")
@ActiveProfiles("test")
class SalesPostWriterWithdrawTest {

    private static final Long SELLER_ID = 7L;
    private static final Long WITHDRAWN_MEMBER_ID = 100L;
    private static final Long OTHER_MEMBER_ID = 200L;
    private static final AtomicLong ORDER_ITEM_SEQ = new AtomicLong(5000);

    @Autowired
    SalesPostWriterWithdrawUseCase salesPostWriterWithdrawUseCase;
    @Autowired
    SalesPostRepository salesPostRepository;
    @Autowired
    SalesPostCommentRepository salesPostCommentRepository;
    @Autowired
    SalesPostCommentHistoryRepository salesPostCommentHistoryRepository;
    @Autowired
    SalesPostReviewRepository salesPostReviewRepository;
    @Autowired
    SalesPostReviewHistoryRepository salesPostReviewHistoryRepository;
    @Autowired
    SalesPostPurchaseRepository salesPostPurchaseRepository;
    @Autowired
    SalesPostProductRepository salesPostProductRepository;
    @Autowired
    SalesPostProductImageRepository salesPostProductImageRepository;
    @Autowired
    SalesPostProductOptionRepository salesPostProductOptionRepository;

    // 다른 댓글 · 리뷰 테스트와 같은 스프링 컨텍스트를 쓰도록 같은 대역을 둔다 (이 테스트에서는 부르지 않는다)
    @MockitoBean
    SalesPostMemberClient salesPostMemberClient;

    Long postId;
    Long otherPostId;

    @BeforeEach
    void setUp() {
        salesPostCommentHistoryRepository.deleteAll();
        salesPostCommentRepository.deleteAll();
        salesPostReviewHistoryRepository.deleteAll();
        salesPostReviewRepository.deleteAll();
        salesPostPurchaseRepository.deleteAll();
        salesPostProductImageRepository.deleteAll();
        salesPostProductOptionRepository.deleteAll();
        salesPostProductRepository.deleteAll();
        salesPostRepository.deleteAll();

        postId = savePost();
        otherPostId = savePost();
    }

    @Test
    @DisplayName("그 회원이 쓴 원댓글 · 답글 · 삭제된 글까지 판매글과 상관없이 작성자만 \"탈퇴한 회원\"으로 바뀌고 내용은 남는다")
    void anonymizesOnlyThatMembersComments() {
        SalesPostComment root = save(SalesPostComment.root(postId, WITHDRAWN_MEMBER_ID, "탈퇴할회원", "정사이즈인가요?", true));
        SalesPostComment othersRoot = save(SalesPostComment.root(otherPostId, OTHER_MEMBER_ID, "남은회원", "배송 얼마나?", false));
        SalesPostComment replyOnOthers = save(SalesPostComment.reply(othersRoot, WITHDRAWN_MEMBER_ID, "탈퇴할회원", "저도 궁금", false));
        SalesPostComment deleted = save(SalesPostComment.root(postId, WITHDRAWN_MEMBER_ID, "탈퇴할회원", "지운 글", false));
        deleted.delete(WITHDRAWN_MEMBER_ID);
        salesPostCommentRepository.save(deleted);

        int changed = salesPostWriterWithdrawUseCase.anonymizeWriter(WITHDRAWN_MEMBER_ID);

        assertThat(changed).isEqualTo(3);
        for (Long id : new Long[]{root.getId(), replyOnOthers.getId(), deleted.getId()}) {
            assertThat(salesPostCommentRepository.findById(id).orElseThrow().getWriterNickname())
                    .isEqualTo(SalesPostComment.WITHDRAWN_NICKNAME);
        }
        SalesPostComment reloadedRoot = salesPostCommentRepository.findById(root.getId()).orElseThrow();
        assertThat(reloadedRoot.getContent()).isEqualTo("정사이즈인가요?");
        assertThat(reloadedRoot.isSecret()).isTrue();
        assertThat(reloadedRoot.getWriterMemberId()).isEqualTo(WITHDRAWN_MEMBER_ID);

        assertThat(salesPostCommentRepository.findById(othersRoot.getId()).orElseThrow().getWriterNickname())
                .isEqualTo("남은회원");
    }

    @Test
    @DisplayName("그 회원이 쓴 리뷰(삭제된 리뷰 포함)도 작성자만 \"탈퇴한 회원\"으로 바뀌고 별점 · 내용은 남는다")
    void anonymizesOnlyThatMembersReviews() {
        SalesPostReview mine = saveReview(WITHDRAWN_MEMBER_ID, "탈퇴할회원", 4, "사이즈가 딱 맞아요. 좋아요.");
        SalesPostReview deletedMine = saveReview(WITHDRAWN_MEMBER_ID, "탈퇴할회원", 1, "별로였어요. 환불하고 싶어요.");
        deletedMine.delete(WITHDRAWN_MEMBER_ID);
        salesPostReviewRepository.save(deletedMine);
        SalesPostReview others = saveReview(OTHER_MEMBER_ID, "남은회원", 5, "배송이 빨라서 좋았어요.");
        save(SalesPostComment.root(postId, WITHDRAWN_MEMBER_ID, "탈퇴할회원", "문의", false));

        int changed = salesPostWriterWithdrawUseCase.anonymizeWriter(WITHDRAWN_MEMBER_ID);

        assertThat(changed).isEqualTo(3);   // 리뷰 2 + 댓글 1
        for (Long id : new Long[]{mine.getId(), deletedMine.getId()}) {
            assertThat(salesPostReviewRepository.findById(id).orElseThrow().getWriterNickname())
                    .isEqualTo(SalesPostComment.WITHDRAWN_NICKNAME);
        }
        SalesPostReview reloaded = salesPostReviewRepository.findById(mine.getId()).orElseThrow();
        assertThat(reloaded.getRating()).isEqualTo(4);
        assertThat(reloaded.getContent()).isEqualTo("사이즈가 딱 맞아요. 좋아요.");
        assertThat(reloaded.getWriterMemberId()).isEqualTo(WITHDRAWN_MEMBER_ID);

        assertThat(salesPostReviewRepository.findById(others.getId()).orElseThrow().getWriterNickname())
                .isEqualTo("남은회원");
    }

    @Test
    @DisplayName("댓글 · 리뷰가 없는 회원이면 아무것도 바뀌지 않는다")
    void noCommentsChangesNothing() {
        save(SalesPostComment.root(postId, OTHER_MEMBER_ID, "남은회원", "문의", false));
        saveReview(OTHER_MEMBER_ID, "남은회원", 5, "배송이 빨라서 좋았어요.");

        assertThat(salesPostWriterWithdrawUseCase.anonymizeWriter(999L)).isZero();
        assertThat(salesPostCommentRepository.findAll()).extracting(SalesPostComment::getWriterNickname)
                .containsExactly("남은회원");
        assertThat(salesPostReviewRepository.findAll()).extracting(SalesPostReview::getWriterNickname)
                .containsExactly("남은회원");
    }

    private SalesPostComment save(SalesPostComment comment) {
        return salesPostCommentRepository.save(comment);
    }

    // 구매확정된 구매 기록으로 리뷰를 바로 저장한다 (작성 자격은 SalesPostReviewCreateTest에서 확인)
    private SalesPostReview saveReview(Long memberId, String nickname, int rating, String content) {
        SalesPostPurchase purchase = SalesPostPurchase.purchased(PurchaseType.INDIVIDUAL_GROUP, 31L,
                ORDER_ITEM_SEQ.incrementAndGet(), memberId, 9L, 90L, SELLER_ID, "반팔 티셔츠", "블랙 / L");
        purchase.confirm(LocalDateTime.now().minusDays(1));
        SalesPostPurchase saved = salesPostPurchaseRepository.save(purchase);
        return salesPostReviewRepository.save(SalesPostReview.write(saved, postId, nickname, rating, content));
    }

    private Long savePost() {
        return salesPostRepository.save(new SalesPost(SELLER_ID, "판매글", "내용", SalesPostSaleStatus.ON_SALE, 0L,
                SalesPostUpdateStatus.NEW, LocalDateTime.now())).getId();
    }
}
