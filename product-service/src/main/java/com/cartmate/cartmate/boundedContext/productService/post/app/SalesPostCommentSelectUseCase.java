package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostComment;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SalesPostCommentResponse;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SalesPostReplyResponse;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostCommentRepository;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.page.PageResponse;
import com.cartmate.cartmate.global.security.SecurityUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 판매글 댓글 목록. 원댓글을 최신순으로 한 페이지(10개) 읽고, 그 답글들을 한 번에 읽어 오래된 순으로 붙인다.
 * 로그인하지 않아도 볼 수 있고, 로그인 정보는 비밀 댓글 열람 · 내 글 표시에만 쓴다.
 */
@Service
@RequiredArgsConstructor
public class SalesPostCommentSelectUseCase {
    public static final int PAGE_SIZE = 10;
    static final String DELETED_CONTENT = "삭제된 댓글입니다.";
    static final String SECRET_CONTENT = "비밀 댓글입니다.";

    private final SalesPostRepository salesPostRepository;
    private final SalesPostCommentRepository salesPostCommentRepository;

    @Transactional(readOnly = true)
    public PageResponse<SalesPostCommentResponse> selectComments(Long postId, int page, SecurityUser viewer) {
        SalesPost post = salesPostRepository.findById(postId)
                .filter(p -> p.getStatus().isPubliclyVisible())
                .orElseThrow(() -> new ServiceException(SalesPostErrorCode.POST_NOT_FOUND));

        Page<SalesPostComment> roots = salesPostCommentRepository.findVisibleRoots(
                postId, PageRequest.of(Math.max(page, 0), PAGE_SIZE));
        Map<Long, List<SalesPostComment>> repliesByRoot = roots.isEmpty()
                ? Map.of()
                : salesPostCommentRepository.findByParentIdInOrderByIdAsc(roots.map(SalesPostComment::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(SalesPostComment::getParentId));

        Viewer v = Viewer.of(viewer, post);
        return PageResponse.of(roots.map(root -> toRootResponse(
                root, visibleReplies(repliesByRoot.getOrDefault(root.getId(), List.of())), v)));
    }

    // 삭제된 답글은 뒤에 살아 있는 답글이 있을 때만 "삭제된 댓글입니다"로 남기고, 아니면 뺀다
    private List<SalesPostComment> visibleReplies(List<SalesPostComment> replies) {
        List<SalesPostComment> result = new ArrayList<>();
        boolean aliveAfter = false;
        for (int i = replies.size() - 1; i >= 0; i--) {
            SalesPostComment reply = replies.get(i);
            if (!reply.isDeleted()) {
                aliveAfter = true;
                result.add(reply);
            } else if (aliveAfter) {
                result.add(reply);
            }
        }
        Collections.reverse(result);
        return result;
    }

    private SalesPostCommentResponse toRootResponse(SalesPostComment root, List<SalesPostComment> replies, Viewer v) {
        boolean canReadSecret = v.canReadSecret(root);
        List<SalesPostReplyResponse> replyResponses = replies.stream()
                .map(reply -> toReplyResponse(reply, canReadSecret, v))
                .toList();
        return new SalesPostCommentResponse(
                root.getId(),
                root.isDeleted() ? null : root.getWriterNickname(),
                contentOf(root, canReadSecret),
                root.isSecret(),
                root.isEdited(),
                root.isDeleted(),
                v.isMine(root),
                root.getCreateDate(),
                replyResponses);
    }

    // 답글의 비밀 여부는 원댓글을 따르므로, 열람 권한도 원댓글 기준으로 판단한 값을 그대로 쓴다
    private SalesPostReplyResponse toReplyResponse(SalesPostComment reply, boolean canReadSecret, Viewer v) {
        return new SalesPostReplyResponse(
                reply.getId(),
                reply.isDeleted() ? null : reply.getWriterNickname(),
                contentOf(reply, canReadSecret),
                reply.isSecret(),
                reply.isSellerReply(),
                reply.isEdited(),
                reply.isDeleted(),
                v.isMine(reply),
                reply.getCreateDate());
    }

    private String contentOf(SalesPostComment comment, boolean canReadSecret) {
        if (comment.isDeleted()) {
            return DELETED_CONTENT;
        }
        if (comment.isSecret() && !canReadSecret) {
            return SECRET_CONTENT;
        }
        return comment.getContent();
    }

    // 보는 사람. 비밀 댓글은 원댓글 작성자 · 그 판매글의 판매자 · 관리자(OTP를 거친)만 내용을 본다
    private record Viewer(Long memberId, boolean isPostSeller, boolean isAdmin) {

        static Viewer of(SecurityUser user, SalesPost post) {
            if (user == null) {
                return new Viewer(null, false, false);
            }
            boolean isPostSeller = user.sellerId() != null && user.sellerId().equals(post.getSellerId());
            boolean isAdmin = "ADMIN".equals(user.role()) && user.passedOtp();
            return new Viewer(user.memberId(), isPostSeller, isAdmin);
        }

        boolean canReadSecret(SalesPostComment root) {
            return isPostSeller || isAdmin || (memberId != null && memberId.equals(root.getWriterMemberId()));
        }

        // 삭제된 글은 수정 · 삭제할 수 없으니 내 글로 표시하지 않는다
        boolean isMine(SalesPostComment comment) {
            return !comment.isDeleted() && memberId != null && memberId.equals(comment.getWriterMemberId());
        }
    }
}
