package com.cartmate.cartmate.boundedContext.productService.post.in;

import com.cartmate.cartmate.boundedContext.productService.post.app.SalesPostFacade;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostReviewRequest;
import com.cartmate.cartmate.boundedContext.productService.post.dto.CreateSalesPostReviewResponse;
import com.cartmate.cartmate.boundedContext.productService.post.dto.SalesPostReviewResponse;
import com.cartmate.cartmate.boundedContext.productService.post.dto.WritableSalesPostReviewResponse;
import com.cartmate.cartmate.global.page.PageResponse;
import com.cartmate.cartmate.global.rsData.RsData;
import com.cartmate.cartmate.global.security.SecurityUser;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/sales-posts")
public class SalesPostReviewController {

    private final SalesPostFacade salesPostFacade;

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
    public RsData<List<WritableSalesPostReviewResponse>> selectWritable(@AuthenticationPrincipal SecurityUser user) {
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
