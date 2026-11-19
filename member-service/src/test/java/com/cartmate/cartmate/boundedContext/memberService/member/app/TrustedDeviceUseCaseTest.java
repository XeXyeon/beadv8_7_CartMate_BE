package com.cartmate.cartmate.boundedContext.memberService.member.app;

import com.cartmate.cartmate.boundedContext.memberService.member.domain.MfaProperties;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.TrustedDevice;
import com.cartmate.cartmate.boundedContext.memberService.member.out.TrustedDeviceRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

// 신뢰 기기 해제: 남의 기기는 못 지우고, 지운 기기가 요청한 브라우저면 쿠키도 지우라고 알린다
class TrustedDeviceUseCaseTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 12, 0);

    private final TrustedDeviceRepository repository = mock(TrustedDeviceRepository.class);
    private final TrustedDeviceUseCase useCase = new TrustedDeviceUseCase(repository, mock(MfaProperties.class));

    private TrustedDevice device(Long memberId, String rawToken) {
        return TrustedDevice.register(memberId, SecureTokens.sha256(rawToken), "Chrome", NOW, NOW.plusDays(30));
    }

    @Test
    void 남의_기기는_해제하지_못한다() {
        when(repository.findById(10L)).thenReturn(Optional.of(device(2L, "token")));

        assertThatThrownBy(() -> useCase.revoke(1L, 10L, "token")).isInstanceOf(ServiceException.class);
        verify(repository, never()).deleteByIdAndMemberId(anyLong(), anyLong());
    }

    @Test
    void 요청한_브라우저의_기기를_해제하면_true() {
        when(repository.findById(10L)).thenReturn(Optional.of(device(1L, "mine")));
        when(repository.deleteByIdAndMemberId(10L, 1L)).thenReturn(1);

        assertThat(useCase.revoke(1L, 10L, "mine")).isTrue();
    }

    @Test
    void 다른_기기를_해제하거나_쿠키가_없으면_false() {
        when(repository.findById(any())).thenReturn(Optional.of(device(1L, "other")));
        when(repository.deleteByIdAndMemberId(10L, 1L)).thenReturn(1);

        assertThat(useCase.revoke(1L, 10L, "mine")).isFalse();
        assertThat(useCase.revoke(1L, 10L, null)).isFalse();
    }

    @Test
    void 동시에_이미_해제됐으면_찾을_수_없음() {
        when(repository.findById(10L)).thenReturn(Optional.of(device(1L, "mine")));
        when(repository.deleteByIdAndMemberId(10L, 1L)).thenReturn(0);

        assertThatThrownBy(() -> useCase.revoke(1L, 10L, "mine")).isInstanceOf(ServiceException.class);
    }
}
