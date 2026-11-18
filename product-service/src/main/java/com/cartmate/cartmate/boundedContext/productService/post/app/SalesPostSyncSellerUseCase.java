package com.cartmate.cartmate.boundedContext.productService.post.app;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSeller;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostSellerRepository;
import com.cartmate.cartmate.shared.member.event.SellerRegisteredEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class SalesPostSyncSellerUseCase {
    private final SalesPostSellerRepository salesPostSellerRepository;

    // 멱등: 같은 판매자 이벤트가 다시 와도(Kafka 재전송, 회원 서비스 백필) 행은 하나. 이미 있으면 표시 정보만 맞춘다.
    @Transactional
    public SalesPostSeller syncSeller(SellerRegisteredEvent seller) {
        return salesPostSellerRepository.findById(seller.sellerId())
                .map(existing -> {
                    existing.sync(seller.sellerType(), seller.displayName(), seller.representativeName(),
                            seller.status(), seller.modifyDate());
                    return existing;
                })
                .orElseGet(() -> salesPostSellerRepository.save(new SalesPostSeller(
                        seller.sellerId(),
                        seller.memberId(),
                        seller.sellerType(),
                        seller.displayName(),
                        seller.representativeName(),
                        seller.status(),
                        seller.createDate(),
                        seller.modifyDate()
                )));
    }
}
