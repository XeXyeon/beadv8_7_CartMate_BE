package com.cartmate.cartmate.boundedContext.etcService.delivery.in;

import com.cartmate.cartmate.boundedContext.etcService.delivery.app.DeliveryClaimLockService;
import com.cartmate.cartmate.boundedContext.etcService.delivery.app.DeliveryFacade;
import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.DeliveryClaimCreateRequest;
import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.DeliveryClaimDecideRequest;
import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.DeliveryClaimResponse;
import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.SellerClaimResponse;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimStatus;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimType;
import com.cartmate.cartmate.global.rsData.RsData;
import com.cartmate.cartmate.global.security.SecurityUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/claims")
@RequiredArgsConstructor
public class ClaimController {

    private final DeliveryFacade deliveryFacade;
    private final DeliveryClaimLockService deliveryClaimLockService;

    // 내가 접수한 교환·환불 목록과 진행 상태를 최신순으로 조회한다.
    @GetMapping("/me")
    public RsData<Page<DeliveryClaimResponse>> getMyClaims(
            @AuthenticationPrincipal SecurityUser user,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return new RsData<>("200-1", "내 교환·환불 목록 조회 성공했습니다.",
                deliveryFacade.getMyClaims(user.memberId(), pageable));
    }

    // 판매자 교환·환불 관리 목록. status는 여러 개를 줄 수 있고(처리대기 탭 = REQUESTED,APPROVED),
    // 생략하면 전체를 조회한다. claimType도 생략하면 교환·환불 모두 조회한다.
    @GetMapping("/seller")
    public RsData<Page<SellerClaimResponse>> getSellerClaims(
            @AuthenticationPrincipal SecurityUser user,
            @RequestParam(required = false) List<ClaimStatus> status,
            @RequestParam(required = false) ClaimType claimType,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return new RsData<>("200-1", "판매자 교환·환불 목록 조회 성공했습니다.",
                deliveryFacade.getSellerClaims(user.memberId(), status, claimType, pageable));
    }

    // 판매자가 접수된 교환·환불의 모든 상품에 승인이나 거절을 한 번에 정해 처리한다. 거절하는 상품은 사유가 필수다.
    @PatchMapping("/{claimId}/decide")
    public RsData<SellerClaimResponse> decideClaim(
            @AuthenticationPrincipal SecurityUser user,
            @PathVariable Long claimId,
            @Valid @RequestBody DeliveryClaimDecideRequest request
    ) {
        return new RsData<>("200-1", "교환·환불이 처리되었습니다.",
                deliveryClaimLockService.decide(claimId, user.memberId(), request.items()));
    }

    // 판매자가 수락한 교환·환불을 처리 완료로 바꾼다.
    @PatchMapping("/{claimId}/complete")
    public RsData<SellerClaimResponse> completeClaim(
            @AuthenticationPrincipal SecurityUser user,
            @PathVariable Long claimId
    ) {
        return new RsData<>("200-1", "교환·환불이 완료 처리되었습니다.",
                deliveryClaimLockService.complete(claimId, user.memberId()));
    }

    // 수령인(구매자) 본인이 배송완료된 배송에서 상품을 골라 교환·환불을 접수한다.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<DeliveryClaimResponse> createClaim(
            @AuthenticationPrincipal SecurityUser user,
            @Valid @RequestBody DeliveryClaimCreateRequest request
    ) {
        return new RsData<>("201-1", "교환·환불이 접수되었습니다.",
                deliveryClaimLockService.create(request.toCommand(user.memberId())));
    }
}
