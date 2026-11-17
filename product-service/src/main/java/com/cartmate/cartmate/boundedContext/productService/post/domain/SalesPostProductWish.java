package com.cartmate.cartmate.boundedContext.productService.post.domain;

import com.cartmate.cartmate.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 판매글 상품 찜. 회원 한 명이 판매글 상품 하나를 찜한 기록이 한 줄이다.
 *
 * <ul>
 *   <li>같은 회원이 같은 판매글 상품을 두 번 찜할 수 없다(유니크 키). 버튼을 빠르게 두 번 눌러도 한 줄만 남는다</li>
 *   <li>찜 해제는 이 줄을 지운다(논리 삭제 X)</li>
 *   <li>판매글 상품이 삭제돼도 이 줄은 남겨 두고, 내 찜 목록을 볼 때 걸러낸다</li>
 * </ul>
 * 판매글 상품 id는 상품 id와 같다(SalesPostProduct는 상품의 복제본).
 */
@Entity
@Table(name = "POST_SALES_POST_PRODUCT_WISH",
        uniqueConstraints = @UniqueConstraint(name = "uk_sales_post_product_wish_member_product",
                columnNames = {"member_id", "sales_post_product_id"}),
        // 내 찜 목록을 찜한 순서(id 역순)로 본다
        indexes = @Index(name = "idx_sales_post_product_wish_member", columnList = "member_id, id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SalesPostProductWish extends BaseIdAndTime {

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private Long salesPostProductId;

    private SalesPostProductWish(Long memberId, Long salesPostProductId) {
        this.memberId = memberId;
        this.salesPostProductId = salesPostProductId;
    }

    public static SalesPostProductWish of(Long memberId, Long salesPostProductId) {
        return new SalesPostProductWish(memberId, salesPostProductId);
    }
}
