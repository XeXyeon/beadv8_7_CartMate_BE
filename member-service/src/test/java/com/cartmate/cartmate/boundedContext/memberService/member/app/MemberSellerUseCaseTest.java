package com.cartmate.cartmate.boundedContext.memberService.member.app;

import com.cartmate.cartmate.boundedContext.memberService.member.domain.Member;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.Seller;
import com.cartmate.cartmate.boundedContext.memberService.member.domain.SellerType;
import com.cartmate.cartmate.boundedContext.memberService.member.dto.SellerRegisterRequest;
import com.cartmate.cartmate.boundedContext.memberService.member.out.AccountVerificationStore;
import com.cartmate.cartmate.boundedContext.memberService.member.out.MemberRepository;
import com.cartmate.cartmate.boundedContext.memberService.member.out.SellerRepository;
import com.cartmate.cartmate.global.event.DomainEventPublisher;
import com.cartmate.cartmate.global.outbox.OutboxTopics;
import com.cartmate.cartmate.global.security.JwtProvider;
import com.cartmate.cartmate.shared.member.event.SellerRegisteredEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 판매자 등록 이벤트가 Outbox 발행 포트로 나가는지 (서비스 분리 후 결제 · 상품에 안 가서 정산 지급이 실패하던 버그)
class MemberSellerUseCaseTest {

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final SellerRepository sellerRepository = mock(SellerRepository.class);
    private final AccountVerificationStore accountVerificationStore = mock(AccountVerificationStore.class);
    private final DomainEventPublisher eventPublisher = mock(DomainEventPublisher.class);
    private final JwtProvider jwtProvider = mock(JwtProvider.class);

    private final MemberSellerUseCase useCase = new MemberSellerUseCase(
            memberRepository, sellerRepository, accountVerificationStore, eventPublisher, jwtProvider);

    @Test
    void 판매자_등록_이벤트를_Outbox_발행_포트로_보낸다() {
        Member member = Member.create("seller@cartmate.com", "encoded", "판매왕", "010-1234-5678",
                LocalDate.of(1990, 1, 1));
        ReflectionTestUtils.setField(member, "id", 7L);
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(accountVerificationStore.isVerified(eq(7L), anyString())).thenReturn(true);
        when(sellerRepository.saveAndFlush(any(Seller.class))).thenAnswer(invocation -> {
            Seller seller = invocation.getArgument(0);
            ReflectionTestUtils.setField(seller, "id", 30L);
            return seller;
        });

        useCase.register(7L, new SellerRegisterRequest(SellerType.BUSINESS, "카트상회", "김대표", "123-45-67890",
                "010-1234-5678", "국민은행", "김대표", "123456789012", true));

        ArgumentCaptor<SellerRegisteredEvent> captor = ArgumentCaptor.forClass(SellerRegisteredEvent.class);
        verify(eventPublisher).publish(captor.capture());
        SellerRegisteredEvent event = captor.getValue();
        assertThat(event.sellerId()).isEqualTo(30L);
        assertThat(event.memberId()).isEqualTo(7L);
        assertThat(event.displayName()).isEqualTo("카트상회");
        assertThat(event.representativeName()).isEqualTo("김대표");
        assertThat(event.aggregateId()).isEqualTo("30");
        assertThat(OutboxTopics.of(SellerRegisteredEvent.class)).isEqualTo("cartmate.member.seller-registered");
    }
}
