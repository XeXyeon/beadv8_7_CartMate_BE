package com.cartmate.cartmate.boundedContext.memberService.member.in.internal;

import com.cartmate.cartmate.boundedContext.memberService.member.app.MemberFacade;
import com.cartmate.cartmate.boundedContext.memberService.member.app.SellerQueryAdapter;
import com.cartmate.cartmate.global.rsData.RsData;
import com.cartmate.cartmate.shared.member.MemberQuery;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Hidden
@RestController
@RequestMapping("/internal/members")
@RequiredArgsConstructor
public class MemberInternalController {

    private final MemberQuery memberQuery;
    private final SellerQueryAdapter sellerQueryAdapter;
    private final MemberFacade memberFacade;

    @GetMapping("/nicknames")
    public RsData<Map<Long, String>> findNicknames(@RequestParam List<Long> ids) {
        return new RsData<>("200-1", "닉네임 조회", memberQuery.findNicknames(ids));
    }

    // 탈퇴 확정 회원 ID: 확정 시각이 from 이상 ~ to 미만 (from이 없으면 처음부터). product가 매일 받아 가 댓글·리뷰·판매글을 정리한다
    @GetMapping("/withdrawn")
    public RsData<List<Long>> findWithdrawnMemberIds(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return new RsData<>("200-1", "탈퇴 확정 회원 조회", memberFacade.findWithdrawnMemberIds(from, to));
    }

    @GetMapping("/{memberId}/seller-id")
    public RsData<Long> findSellerId(@PathVariable Long memberId) {
        return new RsData<>("200-1", "판매자 ID 조회", sellerQueryAdapter.findSellerIdByMemberId(memberId).orElse(null));
    }

    // 운영 백필: 모든 판매자의 등록 이벤트를 Outbox로 다시 발행한다 (결제·상품 판매자 복제본 채우기).
    // 받는 쪽이 멱등이라 여러 번 실행해도 안전하다. 실행 방법: docs/operations/seller-registered-backfill.md
    @PostMapping("/sellers/republish-registered")
    public RsData<Integer> republishSellerRegistered() {
        return new RsData<>("200-1", "판매자 등록 이벤트 다시 발행", memberFacade.republishSellerRegisteredEvents());
    }
}
