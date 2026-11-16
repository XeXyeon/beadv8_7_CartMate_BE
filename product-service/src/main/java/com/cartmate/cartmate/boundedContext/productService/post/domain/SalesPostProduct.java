package com.cartmate.cartmate.boundedContext.productService.post.domain;

import com.cartmate.cartmate.global.jpa.entity.BaseManualIdAndTime;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "POST_PRODUCT")
@NoArgsConstructor
@Getter
public class SalesPostProduct extends BaseManualIdAndTime {
    @Column(nullable = false, columnDefinition = "BIGINT")
    private Long sellerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sales_post_id", nullable = false)
    private SalesPost salesPost;

    @Column(nullable = false, columnDefinition = "BIGINT")
    private Long categoryId;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false, columnDefinition = "BIGINT")
    private Long basePrice;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private SalesPostProductStatus status;

    @OneToMany(mappedBy = "salesPostProduct", cascade = {CascadeType.PERSIST, CascadeType.REMOVE}, orphanRemoval = true)
    @SQLRestriction("status in ('ACTIVE')")
    private List<SalesPostProductImage> imageList = new ArrayList<>();

    // 사진과 같이 삭제되지 않은 옵션만 불러온다(옵션은 논리 삭제)
    @OneToMany(mappedBy = "salesPostProduct", cascade = {CascadeType.PERSIST, CascadeType.REMOVE}, orphanRemoval = true)
    @SQLRestriction("status in ('ACTIVE')")
    private List<SalesPostProductOption> optionList = new ArrayList<>();


    public SalesPostProduct(Long id, Long sellerId, SalesPost salesPost, Long categoryId, String name, String description,
                            Long basePrice, SalesPostProductStatus status, LocalDateTime createDate, LocalDateTime modifyDate) {
        super(id, createDate, modifyDate);
        this.sellerId = sellerId;
        this.salesPost = salesPost;
        this.categoryId = categoryId;
        this.name = name;
        this.description = description;
        this.basePrice = basePrice;
        this.status = status;
    }

    // product의 수정 · 복구 응답으로 이미 있는 복제본을 맞춘다. 사진 · 옵션 목록은 건드리지 않는다
    // (새 객체로 덮어쓰면 빈 목록이 orphanRemoval로 기존 사진 · 옵션 복제본을 지운다)
    public void syncFrom(Long categoryId, String name, String description, Long basePrice,
                         SalesPostProductStatus status, LocalDateTime modifyDate) {
        this.categoryId = categoryId;
        this.name = name;
        this.description = description;
        this.basePrice = basePrice;
        this.status = status;
        copyModifyDate(modifyDate);
    }

    public SalesPostProduct delete() {
        this.status = SalesPostProductStatus.DELETED;
        imageList.forEach(SalesPostProductImage::delete);
        optionList.forEach(SalesPostProductOption::delete);
        return this;
    }

}