package com.cartmate.cartmate.boundedContext.productService.coupon.in;

import com.cartmate.cartmate.boundedContext.productService.coupon.app.CouponFacade;
import com.cartmate.cartmate.shared.member.event.MemberSignedUpEvent;
import com.cartmate.cartmate.global.event.OutboxMessageRelayed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 가입 쿠폰 자동 발급. 회원 서비스의 가입 이벤트를 받는다 (토픽 cartmate.member.member-signed-up).
 * 같은 이벤트가 두 번 와도 회원당 한 장이라 한 번만 발급된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponMemberEventConsumer {

    private final CouponFacade couponFacade;

    // 같은 앱 안 Outbox 릴레이로 받을 때 (모놀리스 시절 경로, 회원이 같은 앱에 있을 때만 온다)
    @TransactionalEventListener(fallbackExecution = true)
    public void handle(OutboxMessageRelayed message) {
        if (message.event() instanceof MemberSignedUpEvent event) {
            issue(event);
        }
    }

    // Kafka로 받을 때 (ProductServiceKafkaConfig): Inbox 트랜잭션 안에서 처리한다.
    // 쿠폰은 한 장마다 새 트랜잭션으로 발급되고, 예상 못 한 실패는 다시 던져 Inbox가 다시 받게 한다(이미 받은 쿠폰은 건너뜀).
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void handle(MemberSignedUpEvent event) {
        issue(event);
    }

    private void issue(MemberSignedUpEvent event) {
        int issued = couponFacade.issueSignupCoupons(event.member().id());
        if (issued > 0) {
            log.info("가입 쿠폰 {}장 발급: 회원 #{}", issued, event.member().id());
        }
    }
}
