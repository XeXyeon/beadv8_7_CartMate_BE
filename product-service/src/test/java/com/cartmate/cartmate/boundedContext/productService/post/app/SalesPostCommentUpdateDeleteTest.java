package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostComment;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostCommentHistory;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostCommentRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostReplyRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.UpdateSalesPostCommentRequest;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostCommentHistoryRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostCommentRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostMemberClient;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductOptionRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.global.exception.ErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * 판매글 댓글 · 답글 수정과 삭제.
 * 작성자 본인만 언제든 수정 · 삭제하고, 수정 전 내용은 이력으로 남는다. 판매 종료된 판매글에서도 할 수 있다.
 */
@SpringBootTest(properties = "cartmate.scheduling.enabled=false")
@ActiveProfiles("test")
class SalesPostCommentUpdateDeleteTest {

    private static final Long SELLER_ID = 7L;
    private static final Long SELLER_MEMBER_ID = 70L;
    private static final Long BUYER_ID = 100L;

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
    @DisplayName("작성자가 수정하면 내용이 바뀌고 수정됨으로 표시되며, 수정 전 내용이 순서대로 이력에 쌓인다")
    void editSavesHistory() {
        Long id = createRoot(postId, "정사이즈인가요?");

        update(postId, id, BUYER_ID, "작게 나오나요?");
        update(postId, id, BUYER_ID, "크게 나오나요?");

        SalesPostComment comment = salesPostCommentRepository.findById(id).orElseThrow();
        assertThat(comment.getContent()).isEqualTo("크게 나오나요?");
        assertThat(comment.isEdited()).isTrue();
        assertThat(comment.isSecret()).isFalse();
        assertThat(historiesOf(id)).extracting(SalesPostCommentHistory::getBeforeContent)
                .containsExactly("정사이즈인가요?", "작게 나오나요?");
    }

    @Test
    @DisplayName("답글도 작성자가 언제든 수정할 수 있다 (뒤에 다른 답글이 있어도)")
    void replyCanBeEditedAnytime() {
        Long rootId = createRoot(postId, "정사이즈인가요?");
        Long replyId = createReply(postId, rootId, SELLER_MEMBER_ID, SELLER_ID, "네, 정사이즈입니다.");
        createReply(postId, rootId, BUYER_ID, null, "그럼 L 사면 되겠네요?");

        update(postId, replyId, SELLER_MEMBER_ID, "조금 작게 나옵니다.");

        assertThat(salesPostCommentRepository.findById(replyId).orElseThrow().getContent())
                .isEqualTo("조금 작게 나옵니다.");
        assertThat(historiesOf(replyId)).extracting(SalesPostCommentHistory::getBeforeContent)
                .containsExactly("네, 정사이즈입니다.");
    }

    @Test
    @DisplayName("내용이 그대로면 이력도 수정됨 표시도 남기지 않는다")
    void sameContentChangesNothing() {
        Long id = createRoot(postId, "정사이즈인가요?");

        update(postId, id, BUYER_ID, "정사이즈인가요?");

        assertThat(salesPostCommentRepository.findById(id).orElseThrow().isEdited()).isFalse();
        assertThat(historiesOf(id)).isEmpty();
    }

    @Test
    @DisplayName("판매자라도 남의 댓글은 수정 · 삭제할 수 없다")
    void onlyWriterCanEditOrDelete() {
        Long id = createRoot(postId, "정사이즈인가요?");

        assertError(() -> update(postId, id, SELLER_MEMBER_ID, "바꿈"), SalesPostErrorCode.COMMENT_EDIT_DENIED);
        assertError(() -> salesPostFacade.deleteComment(postId, id, SELLER_MEMBER_ID),
                SalesPostErrorCode.COMMENT_DELETE_DENIED);
    }

    @Test
    @DisplayName("수정 내용도 1~500자여야 한다")
    void editValidatesContent() {
        Long id = createRoot(postId, "정사이즈인가요?");

        assertError(() -> update(postId, id, BUYER_ID, ""), SalesPostErrorCode.COMMENT_INVALID_CONTENT);
        assertError(() -> update(postId, id, BUYER_ID, "가".repeat(501)), SalesPostErrorCode.COMMENT_INVALID_CONTENT);
    }

    @Test
    @DisplayName("삭제는 표시만 하고 내용은 남기며, 원댓글을 지워도 답글은 그대로다")
    void deleteIsSoftAndKeepsReplies() {
        Long rootId = createRoot(postId, "정사이즈인가요?");
        Long replyId = createReply(postId, rootId, SELLER_MEMBER_ID, SELLER_ID, "네");

        salesPostFacade.deleteComment(postId, rootId, BUYER_ID);

        SalesPostComment root = salesPostCommentRepository.findById(rootId).orElseThrow();
        assertThat(root.isDeleted()).isTrue();
        assertThat(root.getContent()).isEqualTo("정사이즈인가요?");
        assertThat(salesPostCommentRepository.findById(replyId).orElseThrow().isDeleted()).isFalse();
    }

    @Test
    @DisplayName("삭제된 글은 수정하거나 다시 삭제할 수 없다")
    void deletedCommentCannotBeEditedOrDeletedAgain() {
        Long id = createRoot(postId, "정사이즈인가요?");
        salesPostFacade.deleteComment(postId, id, BUYER_ID);

        assertError(() -> update(postId, id, BUYER_ID, "바꿈"), SalesPostErrorCode.COMMENT_NOT_FOUND);
        assertError(() -> salesPostFacade.deleteComment(postId, id, BUYER_ID), SalesPostErrorCode.COMMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("판매 종료된 판매글에서도 기존 글은 수정 · 삭제할 수 있다")
    void canEditAndDeleteOnClosedPost() {
        // 판매 종료된 글에는 새로 쓸 수 없으니, 판매 중일 때 써 둔 댓글을 저장소로 바로 넣어 둔다
        Long closedId = savePost(SalesPostSaleStatus.CLOSED);
        Long id = salesPostCommentRepository.save(
                SalesPostComment.root(closedId, BUYER_ID, "회원100", "정사이즈인가요?", false)).getId();

        update(closedId, id, BUYER_ID, "작게 나오나요?");
        salesPostFacade.deleteComment(closedId, id, BUYER_ID);

        SalesPostComment comment = salesPostCommentRepository.findById(id).orElseThrow();
        assertThat(comment.getContent()).isEqualTo("작게 나오나요?");
        assertThat(comment.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("운영 중지된 판매글의 댓글은 없는 글로 보고 수정 · 삭제할 수 없다")
    void cannotEditOrDeleteOnSuspendedPost() {
        Long id = createRoot(postId, "정사이즈인가요?");
        SalesPost post = salesPostRepository.findById(postId).orElseThrow();
        post.suspendByAdmin();
        salesPostRepository.save(post);

        assertError(() -> update(postId, id, BUYER_ID, "바꿈"), SalesPostErrorCode.POST_NOT_FOUND);
        assertError(() -> salesPostFacade.deleteComment(postId, id, BUYER_ID), SalesPostErrorCode.POST_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 판매글 경로로는 수정 · 삭제할 수 없다")
    void cannotEditThroughOtherPost() {
        Long id = createRoot(postId, "정사이즈인가요?");
        Long otherPostId = savePost(SalesPostSaleStatus.ON_SALE);

        assertError(() -> update(otherPostId, id, BUYER_ID, "바꿈"), SalesPostErrorCode.COMMENT_NOT_FOUND);
        assertError(() -> salesPostFacade.deleteComment(otherPostId, id, BUYER_ID),
                SalesPostErrorCode.COMMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("로그인하지 않으면 수정 · 삭제할 수 없다")
    void requiresLogin() {
        Long id = createRoot(postId, "정사이즈인가요?");

        assertError(() -> update(postId, id, null, "바꿈"), SalesPostErrorCode.COMMENT_LOGIN_REQUIRED);
        assertError(() -> salesPostFacade.deleteComment(postId, id, null), SalesPostErrorCode.COMMENT_LOGIN_REQUIRED);
    }

    private void update(Long postId, Long commentId, Long memberId, String content) {
        salesPostFacade.updateComment(postId, commentId, memberId, new UpdateSalesPostCommentRequest(content));
    }

    private List<SalesPostCommentHistory> historiesOf(Long commentId) {
        return salesPostCommentHistoryRepository.findAll().stream()
                .filter(h -> h.getCommentId().equals(commentId))
                .sorted(Comparator.comparing(SalesPostCommentHistory::getId))
                .toList();
    }

    private Long createRoot(Long postId, String content) {
        return salesPostFacade.createComment(postId, BUYER_ID,
                new CreateSalesPostCommentRequest(content, false)).commentId();
    }

    private Long createReply(Long postId, Long parentId, Long memberId, Long sellerId, String content) {
        return salesPostFacade.createReply(postId, parentId, memberId, sellerId,
                new CreateSalesPostReplyRequest(content)).commentId();
    }

    private Long savePost(SalesPostSaleStatus status) {
        return salesPostRepository.save(new SalesPost(SELLER_ID, "판매글", "내용", status, 0L,
                SalesPostUpdateStatus.NEW, LocalDateTime.now())).getId();
    }

    private void assertError(ThrowingCallable call, ErrorCode errorCode) {
        assertThatThrownBy(call)
                .isInstanceOf(ServiceException.class)
                .extracting("resultCode")
                .isEqualTo(errorCode.resultCode());
    }
}
