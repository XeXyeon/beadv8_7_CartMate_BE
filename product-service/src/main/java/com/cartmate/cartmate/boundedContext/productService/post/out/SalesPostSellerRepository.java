package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPostSeller;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SalesPostSellerRepository extends JpaRepository<SalesPostSeller, Long> {

    // 회원 ID로 판매자 복제본을 찾는다 (탈퇴 완료 회원 중 판매자 찾기). id = 판매자 ID
    List<SalesPostSeller> findAllByMemberIdIn(Collection<Long> memberIds);
}
