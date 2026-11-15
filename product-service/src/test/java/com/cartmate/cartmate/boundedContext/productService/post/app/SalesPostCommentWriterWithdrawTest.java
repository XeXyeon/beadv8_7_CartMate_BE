package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostComment;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSaleStatus;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostUpdateStatus;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostCommentHistoryRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostCommentRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostMemberClient;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductImageRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductOptionRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostProductRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 탈퇴한 회원의 댓글 작성자를 "탈퇴한 회원"으로 바꾸는 처리.
 *
 * <p>member-service에 탈퇴 완료 처리가 없고 탈퇴 이벤트가 서버 간으로 오지 않아, 아직 이벤트로 연결하지 않았다.
 * 그래서 이벤트 수신이 아니라 UseCase를 직접 불러 댓글 쪽 동작만 확인한다.
 */
@SpringBootTest(properties = "cartmate.scheduling.enabled=false")
@ActiveProfiles("test")
class SalesPostCommentWriterWithdrawTest {

    private static final Long SELLER_ID = 7L;
    private static final Long WITHDRAWN_MEMBER_ID = 100L;
    private static final Long OTHER_MEMBER_ID = 200L;

    @Autowired
    SalesPostCommentWriterWithdrawUseCase salesPostCommentWriterWithdrawUseCase;
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

    // 다른 댓글 테스트와 같은 스프링 컨텍스트를 쓰도록 같은 대역을 둔다 (이 테스트에서는 부르지 않는다)
    @MockitoBean
    SalesPostMemberClient salesPostMemberClient;

    Long postId;
    Long otherPostId;

    @BeforeEach
    void setUp() {
        salesPostCommentHistoryRepository.deleteAll();
        salesPostCommentRepository.deleteAll();
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

        int changed = salesPostCommentWriterWithdrawUseCase.anonymizeWriter(WITHDRAWN_MEMBER_ID);

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
    @DisplayName("댓글이 없는 회원이면 아무것도 바뀌지 않는다")
    void noCommentsChangesNothing() {
        save(SalesPostComment.root(postId, OTHER_MEMBER_ID, "남은회원", "문의", false));

        assertThat(salesPostCommentWriterWithdrawUseCase.anonymizeWriter(999L)).isZero();
        assertThat(salesPostCommentRepository.findAll()).extracting(SalesPostComment::getWriterNickname)
                .containsExactly("남은회원");
    }

    private SalesPostComment save(SalesPostComment comment) {
        return salesPostCommentRepository.save(comment);
    }

    private Long savePost() {
        return salesPostRepository.save(new SalesPost(SELLER_ID, "판매글", "내용", SalesPostSaleStatus.ON_SALE, 0L,
                SalesPostUpdateStatus.NEW, LocalDateTime.now())).getId();
    }
}
