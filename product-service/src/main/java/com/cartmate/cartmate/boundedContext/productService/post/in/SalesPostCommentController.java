package com.cartmate.cartmate.boundedContext.productService.post.in;

import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostFacade;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostCommentRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostCommentResponse;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostReplyRequest;
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

    private Long memberIdOf(SecurityUser user) {
        return user == null ? null : user.memberId();
    }
}
