package com.cartmate.cartmate.boundedContext.productService.post.in;

import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostFacade;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostCommentRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostCommentResponse;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostReplyRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostReviewRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostReviewResponse;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SalesPostCommentResponse;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SalesPostReviewResponse;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectSalesPostRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SelectSalesPostResponse;
import com.cartmate.cartmate.boundedContext.productService.post.dto.UpdateSalesPostCommentRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.UpdateSalesPostRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.WritableSalesPostReviewResponse;
import com.cartmate.cartmate.global.page.PageResponse;
import com.cartmate.cartmate.global.rsData.RsData;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.cartmate.cartmate.global.security.SecurityUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/sales-posts")
public class SalesPostController {
    private final SalesPostFacade salesPostFacade;


    @PostMapping
    public ResponseEntity<Void> registerSalesPost(
            @RequestPart("request") CreateSalesPostRequest request,
            @RequestPart("images") List<MultipartFile> images) {
        salesPostFacade.registerSalesPost(request, images);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping
    public ResponseEntity<RsData<PageResponse<SelectSalesPostResponse>>> selectSalesPostAll(
            SelectSalesPostRequest request,
            Pageable pageable) {
        RsData<PageResponse<SelectSalesPostResponse>> result = salesPostFacade.selectSalesPostAll(request, pageable);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/detail/{postId}")
    public ResponseEntity<RsData<SelectSalesPostResponse>> selectSalesPostDetail(@PathVariable Long postId) {
        RsData<SelectSalesPostResponse> result = salesPostFacade.selectSalesPostDetail(postId);
        return ResponseEntity.ok(result);
    }

    // 판매자 본인의 판매글 목록(판매글 관리 화면). 삭제된 글만 빼고 모든 상태, 최신순
    @GetMapping("/sellers/{sellerId}/posts")
    @Operation(operationId = "selectSalesPostsBySeller", summary = "판매자 본인의 판매글 목록")
    public ResponseEntity<RsData<PageResponse<SelectSalesPostResponse>>> selectSalesPostsBySeller(
            @PathVariable Long sellerId,
            Pageable pageable) {
        return ResponseEntity.ok(salesPostFacade.selectSalesPostsBySeller(sellerId, pageable));
    }

    @DeleteMapping("/sellers/{sellerId}/posts/{postId}")
    public ResponseEntity<Void> deleteSalesPost(
            @PathVariable Long sellerId,
            @PathVariable Long postId
    ) {
        salesPostFacade.deleteSalesPostByUser(postId, sellerId);
        return ResponseEntity.status(HttpStatus.OK).build();
    }

    @PatchMapping("/sellers/{sellerId}/posts/{postId}/pause")
    @Operation(operationId = "pauseSalesPost", summary = "판매자 판매 중지")
    public RsData<Void> pause(@PathVariable Long sellerId, @PathVariable Long postId,
                              @AuthenticationPrincipal SecurityUser user) {
        salesPostFacade.pauseSalesPostBySeller(postId, sellerId, user == null ? null : user.memberId());
        return new RsData<>("200-1", "판매를 중지했습니다. 진행 중인 주문은 취소·환불됩니다.");
    }

    @PatchMapping("/sellers/{sellerId}/posts/{postId}/resume")
    @Operation(operationId = "resumeSalesPostBySeller", summary = "판매자 판매 재개")
    public RsData<Void> resume(@PathVariable Long sellerId, @PathVariable Long postId) {
        salesPostFacade.resumeSalesPostBySeller(postId, sellerId);
        return new RsData<>("200-1", "판매를 다시 시작했습니다.");
    }

    @PatchMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> updateSalesPost(
            @RequestPart("request") UpdateSalesPostRequest request,
            @RequestPart(value = "images", required = false) List<MultipartFile> images
    ) {
        salesPostFacade.updateSalesPostByUser(request, images);
        return ResponseEntity.noContent().build();
    }

    // ===== 댓글 =====

    // 로그인 없이도 볼 수 있다. 로그인 정보는 비밀 댓글 열람 · 내 글(isMine) 표시에만 쓴다. 한 페이지에 원댓글 10개
    @GetMapping("/{postId}/comments")
    @Operation(operationId = "selectSalesPostComments", summary = "판매글 댓글 목록")
    public ResponseEntity<RsData<PageResponse<SalesPostCommentResponse>>> selectComments(
            @PathVariable Long postId,
            @RequestParam(defaultValue = "0") int page,
            @AuthenticationPrincipal SecurityUser user) {
        PageResponse<SalesPostCommentResponse> response = salesPostFacade.selectComments(postId, page, user);
        return ResponseEntity.ok(new RsData<>("200-1", "댓글 목록을 조회했습니다.", response));
    }

    @PostMapping("/{postId}/comments")
    @Operation(operationId = "createSalesPostComment", summary = "판매글 댓글 작성")
    public ResponseEntity<RsData<CreateSalesPostCommentResponse>> createComment(
            @PathVariable Long postId,
            @RequestBody CreateSalesPostCommentRequest request,
            @AuthenticationPrincipal SecurityUser user) {
        CreateSalesPostCommentResponse response = salesPostFacade.createComment(postId, memberIdOf(user), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RsData<>("201-1", "댓글을 작성했습니다.", response));
    }

    @PostMapping("/{postId}/comments/{commentId}/replies")
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
    @PatchMapping("/{postId}/comments/{commentId}")
    @Operation(operationId = "updateSalesPostComment", summary = "판매글 댓글 · 답글 수정")
    public RsData<Void> updateComment(
            @PathVariable Long postId,
            @PathVariable Long commentId,
            @RequestBody UpdateSalesPostCommentRequest request,
            @AuthenticationPrincipal SecurityUser user) {
        salesPostFacade.updateComment(postId, commentId, memberIdOf(user), request);
        return new RsData<>("200-1", "댓글을 수정했습니다.");
    }

    // 원댓글 · 답글 모두 이 API로 삭제한다 (soft delete)
    @DeleteMapping("/{postId}/comments/{commentId}")
    @Operation(operationId = "deleteSalesPostComment", summary = "판매글 댓글 · 답글 삭제")
    public RsData<Void> deleteComment(
            @PathVariable Long postId,
            @PathVariable Long commentId,
            @AuthenticationPrincipal SecurityUser user) {
        salesPostFacade.deleteComment(postId, commentId, memberIdOf(user));
        return new RsData<>("200-1", "댓글을 삭제했습니다.");
    }

    // ===== 리뷰 =====

    // 구매 기록(purchaseId)으로 리뷰를 쓴다. 판매글은 구매한 상품으로 찾는다
    @PostMapping("/reviews")
    @Operation(operationId = "createSalesPostReview", summary = "리뷰 작성")
    public ResponseEntity<RsData<CreateSalesPostReviewResponse>> createReview(
            @RequestBody CreateSalesPostReviewRequest request,
            @AuthenticationPrincipal SecurityUser user) {
        CreateSalesPostReviewResponse response = salesPostFacade.createReview(memberIdOf(user), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RsData<>("201-1", "리뷰를 작성했습니다.", response));
    }

    // 로그인한 회원이 리뷰를 쓸 수 있는 상품 (구매확정됐고, 기한이 남았고, 아직 안 쓴 것). 기한이 가까운 순
    @GetMapping("/reviews/writable")
    @Operation(operationId = "selectWritableSalesPostReviews", summary = "리뷰를 쓸 수 있는 상품 목록")
    public RsData<List<WritableSalesPostReviewResponse>> selectWritableReviews(
            @AuthenticationPrincipal SecurityUser user) {
        return new RsData<>("200-1", "리뷰를 쓸 수 있는 상품 목록을 조회했습니다.",
                salesPostFacade.selectWritableReviews(memberIdOf(user)));
    }

    // 판매글 리뷰 목록. 최신순 10개씩, 삭제된 리뷰 제외. 로그인 없이 볼 수 있다 (로그인 정보는 isMine에만 쓴다)
    @GetMapping("/{postId}/reviews")
    @Operation(operationId = "selectSalesPostReviews", summary = "판매글 리뷰 목록")
    public RsData<PageResponse<SalesPostReviewResponse>> selectReviews(
            @PathVariable Long postId,
            @RequestParam(defaultValue = "0") int page,
            @AuthenticationPrincipal SecurityUser user) {
        return new RsData<>("200-1", "리뷰 목록을 조회했습니다.",
                salesPostFacade.selectReviews(postId, page, memberIdOf(user)));
    }

    private Long memberIdOf(SecurityUser user) {
        return user == null ? null : user.memberId();
    }
}
