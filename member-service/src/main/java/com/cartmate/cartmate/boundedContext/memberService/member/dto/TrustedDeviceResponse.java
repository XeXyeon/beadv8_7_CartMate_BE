package com.cartmate.cartmate.boundedContext.memberService.member.dto;

import com.cartmate.cartmate.boundedContext.memberService.member.domain.TrustedDevice;

import java.time.LocalDateTime;

// current: 지금 요청한 브라우저(신뢰 기기 쿠키)가 이 기기인지
public record TrustedDeviceResponse(Long id, String deviceName, LocalDateTime registeredAt,
                                    LocalDateTime lastUsedAt, LocalDateTime expiresAt, boolean current) {

    public static TrustedDeviceResponse from(TrustedDevice device, boolean current) {
        return new TrustedDeviceResponse(device.getId(), device.getDeviceName(), device.getCreateDate(),
                device.getLastUsedAt(), device.getExpiresAt(), current);
    }
}
