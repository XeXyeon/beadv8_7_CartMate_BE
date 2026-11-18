package com.cartmate.cartmate.boundedContext.productService.post.domain;

import com.cartmate.cartmate.global.jpa.entity.BaseManualIdAndTime;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "POST_SELLER")
@NoArgsConstructor
@Getter
public class SalesPostSeller extends BaseManualIdAndTime {
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private Long memberId;

    @Column(name = "seller_type", nullable = false, length = 20)
    private String sellerType;

    @Column(name = "business_name", length = 100)
    private String businessName;

    @Column(name = "representative_name", length = 50)
    private String representativeName;

    // 사업자번호 · 전화번호 · 계좌는 더 이상 채우지 않는다 (SellerRegisteredEvent에서 뺐다, 민감 정보 복제 금지).
    // 칸 삭제는 상품 담당과 따로 정한다. 기존 행의 값은 남아 있다.
    // phone_number는 NULL 허용으로 바꿨다 → 기존 DB는 ALTER TABLE POST_SELLER MODIFY phone_number VARCHAR(20) NULL 필요
    @Column(name = "business_number", length = 20, unique = true)
    private String businessNumber;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "bank_name", length = 50)
    private String bankName;

    @Column(name = "account_holder", length = 50)
    private String accountHolder;

    @Column(name = "account_number", length = 50)
    private String accountNumber;

    @Column(nullable = false, length = 20)
    private String status;

    public SalesPostSeller(Long id, Long memberId, String sellerType, String businessName, String representativeName,
                           String status, LocalDateTime createDate, LocalDateTime modifyDate) {
        super(id, createDate, modifyDate);
        this.memberId = memberId;
        this.sellerType = sellerType;
        this.businessName = businessName;
        this.representativeName = representativeName;
        this.status = status;
    }

    // 같은 판매자 이벤트가 다시 오면(Kafka 재전송, 백필) 새로 만들지 않고 표시 정보만 맞춘다
    public void sync(String sellerType, String businessName, String representativeName, String status,
                     LocalDateTime modifyDate) {
        this.sellerType = sellerType;
        this.businessName = businessName;
        this.representativeName = representativeName;
        this.status = status;
        copyModifyDate(modifyDate);
    }
}
