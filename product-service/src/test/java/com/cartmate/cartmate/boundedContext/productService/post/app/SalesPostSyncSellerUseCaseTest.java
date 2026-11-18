package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSeller;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostSellerRepository;
import com.cartmate.cartmate.shared.member.event.SellerRegisteredEvent;
import com.cartmate.cartmate.support.ProductServiceJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// 판매자 등록 이벤트는 Kafka 재전송 · 회원 서비스 백필로 여러 번 올 수 있다 → 판매자 복제본은 한 행
@ProductServiceJpaTest
@Import(SalesPostSyncSellerUseCase.class)
class SalesPostSyncSellerUseCaseTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 17, 12, 0);

    @Autowired
    private SalesPostSyncSellerUseCase useCase;

    @Autowired
    private SalesPostSellerRepository repository;

    @Test
    void 같은_판매자_이벤트가_두_번_와도_한_행이고_표시_정보는_최신으로() {
        useCase.syncSeller(event("카트상회"));
        useCase.syncSeller(event("카트상회 본점"));

        assertThat(repository.count()).isOne();
        SalesPostSeller seller = repository.findById(30L).orElseThrow();
        assertThat(seller.getBusinessName()).isEqualTo("카트상회 본점");
        assertThat(seller.getRepresentativeName()).isEqualTo("김대표");
        assertThat(seller.getAccountNumber()).isNull();
        assertThat(seller.getPhoneNumber()).isNull();
    }

    private static SellerRegisteredEvent event(String displayName) {
        return new SellerRegisteredEvent(30L, 7L, "BUSINESS", displayName, "김대표", "ACTIVE", NOW, NOW);
    }
}
