package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostComment;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostCommentRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostReplyRequest;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostCommentHistoryRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostCommentRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductOptionRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.global.exception.ErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.http.InternalApiClient.InternalServerUnavailableException;
import com.cartmate.cartmate.support.ProductServiceTestSupport;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 판매글 원댓글 · 답글 작성.
 * 답글은 그 판매글의 판매자(sellerId 일치)나 원댓글 작성자만 쓸 수 있고, 판매 중 · 판매 중지에서만 새로 쓸 수 있다.
 */
class SalesPostCommentCreateTest extends ProductServiceTestSupport {

    private static final Long SELLER_ID = 7L;          // 이 판매글의 판매자
    private static final Long SELLER_MEMBER_ID = 70L;
    private static final Long OTHER_SELLER_ID = 8L;    // 다른 판매글의 판매자
    private static final Long OTHER_SELLER_MEMBER_ID = 80L;
    private static final Long BUYER_ID = 100L;         // 원댓글 작성자
    private static final Long OTHER_BUYER_ID = 200L;

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
    @DisplayName("원댓글은 작성 당시 닉네임과 비밀 여부를 저장하고, 판매자 답글 · 수정됨 · 삭제 표시는 꺼져 있다")
    void createsRootComment() {
        Long id = createRoot(postId, BUYER_ID, "정사이즈인가요?", true);

        SalesPostComment comment = salesPostCommentRepository.findById(id).orElseThrow();
        assertThat(comment.getSalesPostId()).isEqualTo(postId);
        assertThat(comment.isRoot()).isTrue();
        assertThat(comment.getWriterMemberId()).isEqualTo(BUYER_ID);
        assertThat(comment.getWriterNickname()).isEqualTo("회원100");
        assertThat(comment.getContent()).isEqualTo("정사이즈인가요?");
        assertThat(comment.isSecret()).isTrue();
        assertThat(comment.isSellerReply()).isFalse();
        assertThat(comment.isEdited()).isFalse();
        assertThat(comment.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("이 판매글의 판매자가 쓴 답글은 판매자 답글로 표시되고, 비밀 여부는 원댓글을 따른다")
    void sellerReplyIsMarkedAndInheritsSecret() {
        Long rootId = createRoot(postId, BUYER_ID, "주소 알려드려요", true);

        Long replyId = createReply(postId, rootId, SELLER_MEMBER_ID, SELLER_ID, "확인했습니다");

        SalesPostComment reply = salesPostCommentRepository.findById(replyId).orElseThrow();
        assertThat(reply.getParentId()).isEqualTo(rootId);
        assertThat(reply.isSellerReply()).isTrue();
        assertThat(reply.isSecret()).isTrue();
        assertThat(reply.getWriterNickname()).isEqualTo("회원70");
    }

    @Test
    @DisplayName("원댓글 작성자도 답글을 쓸 수 있고, 판매자 답글로 표시되지 않는다")
    void rootWriterCanReply() {
        Long rootId = createRoot(postId, BUYER_ID, "정사이즈인가요?", false);

        Long replyId = createReply(postId, rootId, BUYER_ID, null, "그럼 L 사면 되겠네요?");

        SalesPostComment reply = salesPostCommentRepository.findById(replyId).orElseThrow();
        assertThat(reply.isSellerReply()).isFalse();
        assertThat(reply.isSecret()).isFalse();
    }

    @Test
    @DisplayName("다른 판매글의 판매자는 sellerId가 있어도 일반 사용자처럼 답글을 쓸 수 없다")
    void otherSellerCannotReply() {
        Long rootId = createRoot(postId, BUYER_ID, "정사이즈인가요?", false);

        assertError(() -> createReply(postId, rootId, OTHER_SELLER_MEMBER_ID, OTHER_SELLER_ID, "저도 궁금해요"),
                SalesPostErrorCode.COMMENT_REPLY_DENIED);
    }

    @Test
    @DisplayName("원댓글 작성자가 아닌 일반 사용자는 답글을 쓸 수 없다")
    void otherBuyerCannotReply() {
        Long rootId = createRoot(postId, BUYER_ID, "정사이즈인가요?", false);

        assertError(() -> createReply(postId, rootId, OTHER_BUYER_ID, null, "저도 궁금해요"),
                SalesPostErrorCode.COMMENT_REPLY_DENIED);
    }

    @Test
    @DisplayName("답글에는 답글을 달 수 없다")
    void cannotReplyToReply() {
        Long rootId = createRoot(postId, BUYER_ID, "정사이즈인가요?", false);
        Long replyId = createReply(postId, rootId, SELLER_MEMBER_ID, SELLER_ID, "네");

        assertError(() -> createReply(postId, replyId, BUYER_ID, null, "감사합니다"),
                SalesPostErrorCode.COMMENT_REPLY_TO_REPLY);
    }

    @Test
    @DisplayName("삭제된 원댓글에는 답글을 달 수 없다")
    void cannotReplyToDeletedRoot() {
        Long rootId = createRoot(postId, BUYER_ID, "정사이즈인가요?", false);
        salesPostFacade.deleteComment(postId, rootId, BUYER_ID);

        assertError(() -> createReply(postId, rootId, SELLER_MEMBER_ID, SELLER_ID, "네"),
                SalesPostErrorCode.COMMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 판매글의 댓글을 부모로 지정하면 없는 댓글로 본다")
    void cannotReplyToCommentOfOtherPost() {
        Long otherPostId = savePost(SalesPostSaleStatus.ON_SALE);
        Long rootOfOtherPost = createRoot(otherPostId, BUYER_ID, "다른 글 문의", false);

        assertError(() -> createReply(postId, rootOfOtherPost, BUYER_ID, null, "답글"),
                SalesPostErrorCode.COMMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("판매 중지된 판매글에는 원댓글 · 답글을 쓸 수 있다")
    void canWriteOnPausedPost() {
        Long pausedId = savePost(SalesPostSaleStatus.PAUSED);

        Long rootId = createRoot(pausedId, BUYER_ID, "언제 다시 파나요?", false);
        Long replyId = createReply(pausedId, rootId, SELLER_MEMBER_ID, SELLER_ID, "다음 주에 열어요");

        assertThat(salesPostCommentRepository.findById(rootId)).isPresent();
        assertThat(salesPostCommentRepository.findById(replyId)).isPresent();
    }

    @Test
    @DisplayName("판매 종료된 판매글에는 새로 쓸 수 없다")
    void cannotWriteOnClosedPost() {
        Long closedId = savePost(SalesPostSaleStatus.CLOSED);

        assertError(() -> createRoot(closedId, BUYER_ID, "재입고 되나요?", false),
                SalesPostErrorCode.COMMENT_POST_NOT_WRITABLE);
    }

    @ParameterizedTest
    @EnumSource(value = SalesPostSaleStatus.class, names = {"SUSPENDED", "DELETED", "DELETING", "PENDING"})
    @DisplayName("다른 사용자에게 보이지 않는 판매글에는 없는 글로 응답한다")
    void hiddenPostIsNotFound(SalesPostSaleStatus status) {
        Long hiddenId = savePost(status);

        assertError(() -> createRoot(hiddenId, BUYER_ID, "문의", false), SalesPostErrorCode.POST_NOT_FOUND);
    }

    @Test
    @DisplayName("내용은 비어 있으면 안 되고 500자까지 쓸 수 있다")
    void validatesContentLength() {
        assertError(() -> createRoot(postId, BUYER_ID, "   ", false), SalesPostErrorCode.COMMENT_INVALID_CONTENT);
        assertError(() -> createRoot(postId, BUYER_ID, "가".repeat(501), false),
                SalesPostErrorCode.COMMENT_INVALID_CONTENT);

        Long id = createRoot(postId, BUYER_ID, "가".repeat(500), false);
        assertThat(salesPostCommentRepository.findById(id)).isPresent();
    }

    @Test
    @DisplayName("로그인하지 않으면 회원 서버를 부르지 않고 막는다")
    void requiresLogin() {
        assertError(() -> salesPostFacade.createComment(postId, null,
                new CreateSalesPostCommentRequest("문의", false)), SalesPostErrorCode.COMMENT_LOGIN_REQUIRED);

        verify(salesPostMemberClient, never()).findNickname(anyLong());
    }

    @Test
    @DisplayName("회원 서버가 응답하지 않으면 닉네임 없이 저장하지 않고 작성이 실패한다")
    void failsWhenMemberServerIsDown() {
        given(salesPostMemberClient.findNickname(BUYER_ID))
                .willThrow(new InternalServerUnavailableException("MEMBER", "회원", null));

        assertThatThrownBy(() -> createRoot(postId, BUYER_ID, "문의", false))
                .isInstanceOf(InternalServerUnavailableException.class);
        assertThat(salesPostCommentRepository.count()).isZero();
    }

    private Long createRoot(Long postId, Long memberId, String content, boolean isSecret) {
        return salesPostFacade.createComment(postId, memberId,
                new CreateSalesPostCommentRequest(content, isSecret)).commentId();
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
