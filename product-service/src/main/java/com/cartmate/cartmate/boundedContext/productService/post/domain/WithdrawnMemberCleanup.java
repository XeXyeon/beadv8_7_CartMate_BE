package com.cartmate.cartmate.boundedContext.productService.post.domain;

import com.cartmate.cartmate.global.jpa.entity.BaseManualIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 탈퇴 회원 댓글 · 리뷰 정리의 마지막 성공 시각. 테이블에 항상 한 줄(id = 1)만 둔다.
 * 다음 실행은 lastSucceededAt부터 받고, 성공하면 이번 실행의 to로 바꾼다. 실패하면 그대로 둬서 다음 실행이 그 기간까지 받는다.
 */
@Entity
@Table(name = "POST_WITHDRAWN_MEMBER_CLEANUP")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WithdrawnMemberCleanup extends BaseManualIdAndTime {

    public static final Long SINGLE_ROW_ID = 1L;

    @Column(nullable = false)
    private LocalDateTime lastSucceededAt;

    private WithdrawnMemberCleanup(LocalDateTime succeededAt, LocalDateTime now) {
        super(SINGLE_ROW_ID, now, now);
        this.lastSucceededAt = succeededAt;
    }

    // 첫 성공
    public static WithdrawnMemberCleanup firstSucceeded(LocalDateTime succeededAt, LocalDateTime now) {
        return new WithdrawnMemberCleanup(succeededAt, now);
    }

    public void succeeded(LocalDateTime succeededAt, LocalDateTime now) {
        this.lastSucceededAt = succeededAt;
        copyModifyDate(now);
    }
}
