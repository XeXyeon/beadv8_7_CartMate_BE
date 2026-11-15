package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostCommentRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostReplyRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SalesPostCommentResponse;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SalesPostReplyResponse;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostCommentHistoryRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostCommentRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostMemberClient;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductOptionRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.page.PageResponse;
import com.cartmate.cartmate.global.security.SecurityUser;
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
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * 판매글 댓글 목록: 원댓글 최신순 10개씩, 답글은 원댓글 아래 오래된 순.
 * 삭제된 글은 뒤에 살아 있는 글이 있을 때만 "삭제된 댓글입니다."로 남고,
 * 비밀 댓글은 원댓글 작성자 · 그 판매글의 판매자 · 관리자(OTP 통과)만 내용을 본다 (그 외에는 닉네임만).
 */
@SpringBootTest(properties = "cartmate.scheduling.enabled=false")
@ActiveProfiles("test")
class SalesPostCommentSelectTest {

    private static final Long SELLER_ID = 7L;
    private static final Long SELLER_MEMBER_ID = 70L;
    private static final Long OTHER_SELLER_ID = 8L;
    private static final Long OTHER_SELLER_MEMBER_ID = 80L;
    private static final Long BUYER_ID = 100L;
    private static final Long OTHER_BUYER_ID = 200L;
    private static final Long ADMIN_MEMBER_ID = 1L;

    private static final SecurityUser BUYER = new SecurityUser(BUYER_ID, "buyer@test.com", "USER", null);
    private static final SecurityUser OTHER_BUYER = new SecurityUser(OTHER_BUYER_ID, "other@test.com", "USER", null);
    private static final SecurityUser POST_SELLER = new SecurityUser(SELLER_MEMBER_ID, "seller@test.com", "USER", SELLER_ID);
    private static final SecurityUser OTHER_SELLER =
            new SecurityUser(OTHER_SELLER_MEMBER_ID, "seller2@test.com", "USER", OTHER_SELLER_ID);
    private static final SecurityUser ADMIN_WITH_OTP =
            new SecurityUser(ADMIN_MEMBER_ID, "admin@test.com", "ADMIN", null, List.of(SecurityUser.AMR_OTP));
    private static final SecurityUser ADMIN_WITHOUT_OTP =
            new SecurityUser(ADMIN_MEMBER_ID, "admin@test.com", "ADMIN", null, List.of("pwd"));

    @Autowired
    SalesPostFacade salesPostFacade;
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

    @MockitoBean
    SalesPostMemberClient salesPostMemberClient;

    Long postId;

    @BeforeEach
    void setUp() {
        salesPostCommentHistoryRepository.deleteAll();
        salesPostCommentRepository.deleteAll();
        salesPostProductImageRepository.deleteAll();
        salesPostProductOptionRepository.deleteAll();
        salesPostProductRepository.deleteAll();
        salesPostRepository.deleteAll();

        given(salesPostMemberClient.findNickname(anyLong())).willAnswer(inv -> "회원" + inv.getArgument(0));
        postId = savePost(SalesPostSaleStatus.ON_SALE);
    }

    @Test
    @DisplayName("원댓글은 최신순으로 10개씩 나뉘고, 전체 개수 · 페이지 수가 함께 온다")
    void paginatesRootsNewestFirst() {
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            ids.add(createRoot(BUYER_ID, "문의 " + i, false));
        }

        PageResponse<SalesPostCommentResponse> first = select(0, null);
        PageResponse<SalesPostCommentResponse> second = select(1, null);

        assertThat(first.content()).extracting(SalesPostCommentResponse::commentId)
                .containsExactlyElementsOf(ids.reversed().subList(0, 10));
        assertThat(second.content()).extracting(SalesPostCommentResponse::commentId)
                .containsExactly(ids.get(1), ids.get(0));
        assertThat(first.size()).isEqualTo(10);
        assertThat(first.totalElements()).isEqualTo(12);
        assertThat(first.totalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("마지막보다 큰 페이지는 빈 목록이고, 음수 페이지는 첫 페이지로 본다")
    void outOfRangePages() {
        Long id = createRoot(BUYER_ID, "문의", false);

        assertThat(select(5, null).content()).isEmpty();
        assertThat(select(-1, null).content()).extracting(SalesPostCommentResponse::commentId).containsExactly(id);
    }

    @Test
    @DisplayName("답글은 원댓글 아래 오래된 순으로 붙고, 판매자 답글이 표시된다")
    void repliesOldestFirstUnderRoot() {
        Long rootId = createRoot(BUYER_ID, "정사이즈인가요?", false);
        Long r1 = createReply(rootId, SELLER_MEMBER_ID, SELLER_ID, "네, 정사이즈입니다.");
        Long r2 = createReply(rootId, BUYER_ID, null, "그럼 L 사면 되겠네요?");

        SalesPostCommentResponse root = select(0, null).content().getFirst();

        assertThat(root.replies())
                .extracting(SalesPostReplyResponse::commentId, SalesPostReplyResponse::writerNickname,
                        SalesPostReplyResponse::isSellerReply)
                .containsExactly(tuple(r1, "회원70", true), tuple(r2, "회원100", false));
    }

    @Test
    @DisplayName("답글이 남은 원댓글을 지우면 \"삭제된 댓글입니다.\"로 남고 닉네임은 숨긴다")
    void deletedRootWithAliveReplyIsPlaceholder() {
        Long rootId = createRoot(BUYER_ID, "정사이즈인가요?", false);
        createReply(rootId, SELLER_MEMBER_ID, SELLER_ID, "네");
        salesPostFacade.deleteComment(postId, rootId, BUYER_ID);

        SalesPostCommentResponse root = select(0, null).content().getFirst();

        assertThat(root.isDeleted()).isTrue();
        assertThat(root.content()).isEqualTo("삭제된 댓글입니다.");
        assertThat(root.writerNickname()).isNull();
        assertThat(root.replies()).hasSize(1);
    }

    @Test
    @DisplayName("답글 없는 원댓글이나 답글까지 모두 지워진 원댓글은 목록과 전체 개수에서 빠진다")
    void deletedRootWithoutAliveReplyIsExcluded() {
        Long alone = createRoot(BUYER_ID, "혼자 지운 문의", false);
        salesPostFacade.deleteComment(postId, alone, BUYER_ID);

        Long withReply = createRoot(BUYER_ID, "답글까지 지운 문의", false);
        Long reply = createReply(withReply, SELLER_MEMBER_ID, SELLER_ID, "네");
        salesPostFacade.deleteComment(postId, withReply, BUYER_ID);
        salesPostFacade.deleteComment(postId, reply, SELLER_MEMBER_ID);

        Long alive = createRoot(BUYER_ID, "살아 있는 문의", false);

        PageResponse<SalesPostCommentResponse> page = select(0, null);
        assertThat(page.content()).extracting(SalesPostCommentResponse::commentId).containsExactly(alive);
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("삭제된 답글은 뒤에 살아 있는 답글이 있을 때만 남고, 맨 뒤 쪽이면 빠진다")
    void deletedReplyRules() {
        Long rootId = createRoot(BUYER_ID, "정사이즈인가요?", false);
        Long r1 = createReply(rootId, SELLER_MEMBER_ID, SELLER_ID, "네");
        Long r2 = createReply(rootId, BUYER_ID, null, "그럼 L?");
        Long r3 = createReply(rootId, SELLER_MEMBER_ID, SELLER_ID, "L 추천드려요");
        salesPostFacade.deleteComment(postId, r1, SELLER_MEMBER_ID);
        salesPostFacade.deleteComment(postId, r3, SELLER_MEMBER_ID);

        SalesPostCommentResponse root = select(0, null).content().getFirst();

        assertThat(root.replies())
                .extracting(SalesPostReplyResponse::commentId, SalesPostReplyResponse::isDeleted,
                        SalesPostReplyResponse::content, SalesPostReplyResponse::writerNickname)
                .containsExactly(
                        tuple(r1, true, "삭제된 댓글입니다.", null),
                        tuple(r2, false, "그럼 L?", "회원100"));
    }

    @Test
    @DisplayName("비밀 댓글은 로그인하지 않았거나 권한이 없으면 닉네임만 보이고 내용은 답글까지 가려진다")
    void secretIsMaskedForOthers() {
        Long rootId = createRoot(BUYER_ID, "주소는 서울시...", true);
        createReply(rootId, SELLER_MEMBER_ID, SELLER_ID, "확인했습니다");

        for (SecurityUser viewer : new SecurityUser[]{null, OTHER_BUYER, OTHER_SELLER, ADMIN_WITHOUT_OTP}) {
            SalesPostCommentResponse root = select(0, viewer).content().getFirst();
            assertThat(root.isSecret()).isTrue();
            assertThat(root.writerNickname()).isEqualTo("회원100");
            assertThat(root.content()).isEqualTo("비밀 댓글입니다.");
            assertThat(root.replies()).extracting(SalesPostReplyResponse::writerNickname,
                            SalesPostReplyResponse::content)
                    .containsExactly(tuple("회원70", "비밀 댓글입니다."));
        }
    }

    @Test
    @DisplayName("비밀 댓글은 원댓글 작성자 · 그 판매글의 판매자 · OTP를 거친 관리자에게는 답글까지 보인다")
    void secretIsVisibleToAllowedViewers() {
        Long rootId = createRoot(BUYER_ID, "주소는 서울시...", true);
        createReply(rootId, SELLER_MEMBER_ID, SELLER_ID, "확인했습니다");

        for (SecurityUser viewer : new SecurityUser[]{BUYER, POST_SELLER, ADMIN_WITH_OTP}) {
            SalesPostCommentResponse root = select(0, viewer).content().getFirst();
            assertThat(root.content()).isEqualTo("주소는 서울시...");
            assertThat(root.replies()).extracting(SalesPostReplyResponse::content).containsExactly("확인했습니다");
        }
    }

    @Test
    @DisplayName("내가 쓴 글이면 isMine이고, 삭제된 내 글이나 로그인하지 않은 경우는 아니다")
    void marksMine() {
        Long mine = createRoot(BUYER_ID, "내 문의", false);
        Long reply = createReply(mine, SELLER_MEMBER_ID, SELLER_ID, "답변");
        Long deletedMine = createRoot(BUYER_ID, "지울 문의", false);
        createReply(deletedMine, SELLER_MEMBER_ID, SELLER_ID, "답변");
        salesPostFacade.deleteComment(postId, deletedMine, BUYER_ID);

        List<SalesPostCommentResponse> asBuyer = select(0, BUYER).content();
        assertThat(asBuyer).extracting(SalesPostCommentResponse::commentId, SalesPostCommentResponse::isMine)
                .containsExactly(tuple(deletedMine, false), tuple(mine, true));
        assertThat(asBuyer.get(1).replies()).extracting(SalesPostReplyResponse::isMine).containsExactly(false);

        List<SalesPostCommentResponse> asSeller = select(0, POST_SELLER).content();
        assertThat(asSeller.get(1).replies()).extracting(SalesPostReplyResponse::commentId,
                SalesPostReplyResponse::isMine).containsExactly(tuple(reply, true));

        assertThat(select(0, null).content()).extracting(SalesPostCommentResponse::isMine)
                .containsOnly(false);
    }

    @Test
    @DisplayName("판매 종료된 판매글의 댓글은 볼 수 있고, 운영 중지된 판매글은 없는 글로 응답한다")
    void visibilityByPostStatus() {
        Long closedId = savePost(SalesPostSaleStatus.CLOSED);
        Long suspendedId = savePost(SalesPostSaleStatus.SUSPENDED);

        assertThat(salesPostFacade.selectComments(closedId, 0, null).content()).isEmpty();
        assertThatThrownBy(() -> salesPostFacade.selectComments(suspendedId, 0, POST_SELLER))
                .isInstanceOf(ServiceException.class)
                .extracting("resultCode")
                .isEqualTo(SalesPostErrorCode.POST_NOT_FOUND.resultCode());
    }

    private PageResponse<SalesPostCommentResponse> select(int page, SecurityUser viewer) {
        return salesPostFacade.selectComments(postId, page, viewer);
    }

    private Long createRoot(Long memberId, String content, boolean isSecret) {
        return salesPostFacade.createComment(postId, memberId,
                new CreateSalesPostCommentRequest(content, isSecret)).commentId();
    }

    private Long createReply(Long parentId, Long memberId, Long sellerId, String content) {
        return salesPostFacade.createReply(postId, parentId, memberId, sellerId,
                new CreateSalesPostReplyRequest(content)).commentId();
    }

    private Long savePost(SalesPostSaleStatus status) {
        return salesPostRepository.save(new SalesPost(SELLER_ID, "판매글", "내용", status, 0L,
                SalesPostUpdateStatus.NEW, LocalDateTime.now())).getId();
    }
}
