package com.cartmate.cartmate.boundedContext.productService.post.in;

import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostFacade;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostCommentRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostCommentResponse;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostReplyRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SalesPostCommentResponse;
import com.cartmate.cartmate.boundedContext.productService.post.dto.UpdateSalesPostCommentRequest;
import com.cartmate.cartmate.global.page.PageResponse;
import com.cartmate.cartmate.global.rsData.RsData;
import com.cartmate.cartmate.global.security.SecurityUser;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/sales-posts/{postId}/comments")
public class SalesPostCommentController {

    private final SalesPostFacade salesPostFacade;

    // 로그인 없이도 볼 수 있다. 로그인 정보는 비밀 댓글 열람 · 내 글(isMine) 표시에만 쓴다. 한 페이지에 원댓글 10개
    @GetMapping
    @Operation(operationId = "selectSalesPostComments", summary = "판매글 댓글 목록")
    public ResponseEntity<RsData<PageResponse<SalesPostCommentResponse>>> selectComments(
            @PathVariable Long postId,
            @RequestParam(defaultValue = "0") int page,
            @AuthenticationPrincipal SecurityUser user) {
        PageResponse<SalesPostCommentResponse> response = salesPostFacade.selectComments(postId, page, user);
        return ResponseEntity.ok(new RsData<>("200-1", "댓글 목록을 조회했습니다.", response));
    }

    @PostMapping
    @Operation(operationId = "createSalesPostComment", summary = "판매글 댓글 작성")
    public ResponseEntity<RsData<CreateSalesPostCommentResponse>> createComment(
            @PathVariable Long postId,
            @RequestBody CreateSalesPostCommentRequest request,
            @AuthenticationPrincipal SecurityUser user) {
        CreateSalesPostCommentResponse response = salesPostFacade.createComment(postId, memberIdOf(user), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RsData<>("201-1", "댓글을 작성했습니다.", response));
    }

    @PostMapping("/{commentId}/replies")
    @Operation(operationId = "createSalesPostReply", summary = "판매글 댓글에 답글 작성")
    public ResponseEntity<RsData<CreateSalesPostCommentResponse>> createReply(
            @PathVariable Long postId,
            @PathVariable Long commentId,
            @RequestBody CreateSalesPostReplyRequest request,
            @AuthenticationPrincipal SecurityUser user) {
        CreateSalesPostCommentResponse response = salesPostFacade.createReply(
                postId, commentId, memberIdOf(user), user == null ? null : user.sellerId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RsData<>("201-1", "답글을 작성했습니다.", response));
    }

    // 원댓글 · 답글 모두 이 API로 수정한다 (한 테이블)
    @PatchMapping("/{commentId}")
    @Operation(operationId = "updateSalesPostComment", summary = "판매글 댓글 · 답글 수정")
    public RsData<Void> updateComment(
            @PathVariable Long postId,
            @PathVariable Long commentId,
            @RequestBody UpdateSalesPostCommentRequest request,
            @AuthenticationPrincipal SecurityUser user) {
        salesPostFacade.updateComment(postId, commentId, memberIdOf(user), request);
        return new RsData<>("200-1", "댓글을 수정했습니다.");
    }

    private Long memberIdOf(SecurityUser user) {
        return user == null ? null : user.memberId();
    }
}
