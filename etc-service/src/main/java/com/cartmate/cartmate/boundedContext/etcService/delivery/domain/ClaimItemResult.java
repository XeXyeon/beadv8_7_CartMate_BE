package com.cartmate.cartmate.boundedContext.etcService.delivery.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ClaimItemResult {
    APPROVED("승인"),
    REJECTED("거절");

    private final String label;
}
