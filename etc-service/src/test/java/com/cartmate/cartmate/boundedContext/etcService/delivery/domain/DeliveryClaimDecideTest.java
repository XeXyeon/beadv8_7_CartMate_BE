package com.cartmate.cartmate.boundedContext.etcService.delivery.domain;

import com.cartmate.cartmate.global.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryClaimDecideTest {

    private DeliveryClaim claim(ClaimType type, ClaimReason reason) {
        return DeliveryClaim.create(null, 1L, type, reason, null);
    }

    @Test
    @DisplayName("구매자 사정 반품은 확인 중으로 시작한다")
    void create_buyerReasonRefund_startsVerifying() {
        DeliveryClaim claim = claim(ClaimType.REFUND, ClaimReason.SIMPLE_CHANGE);

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.VERIFYING);
        assertThat(claim.needsVerification()).isTrue();
    }

    @Test
    @DisplayName("판매자 탓 반품은 바로 접수로 시작한다")
    void create_sellerFaultRefund_startsRequested() {
        DeliveryClaim claim = claim(ClaimType.REFUND, ClaimReason.PRODUCT_DEFECT);

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REQUESTED);
        assertThat(claim.needsVerification()).isFalse();
    }

    @Test
    @DisplayName("교환은 사유와 상관없이 바로 접수로 시작한다")
    void create_exchange_startsRequested() {
        DeliveryClaim claim = claim(ClaimType.EXCHANGE, ClaimReason.SIMPLE_CHANGE);

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REQUESTED);
    }

    @Test
    @DisplayName("접수 검사를 통과하면 확인 중에서 접수가 된다")
    void accept_verifying_becomesRequested() {
        DeliveryClaim claim = claim(ClaimType.REFUND, ClaimReason.SIMPLE_CHANGE);

        assertThat(claim.accept()).isTrue();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REQUESTED);
    }

    @Test
    @DisplayName("이미 접수된 클레임에 같은 결과가 다시 와도 아무것도 바꾸지 않는다")
    void accept_notVerifying_isNoop() {
        DeliveryClaim claim = claim(ClaimType.REFUND, ClaimReason.PRODUCT_DEFECT);

        assertThat(claim.accept()).isFalse();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REQUESTED);
    }

    @Test
    @DisplayName("접수 검사에서 거절되면 접수 불가가 되고 이유 코드가 남는다")
    void notAccept_storesCode() {
        DeliveryClaim claim = claim(ClaimType.REFUND, ClaimReason.SIMPLE_CHANGE);

        assertThat(claim.notAccept("ORDER_RETURN_COUPON_MINIMUM")).isTrue();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.NOT_ACCEPTED);
        assertThat(claim.getNotAcceptedCode()).isEqualTo("ORDER_RETURN_COUPON_MINIMUM");
    }

    @Test
    @DisplayName("이미 접수된 클레임은 늦게 온 거절 결과로 접수 불가가 되지 않는다")
    void notAccept_alreadyRequested_isNoop() {
        DeliveryClaim claim = claim(ClaimType.REFUND, ClaimReason.SIMPLE_CHANGE);
        claim.accept();

        assertThat(claim.notAccept("ORDER_RETURN_COUPON_MINIMUM")).isFalse();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REQUESTED);
    }

    @Test
    @DisplayName("반품에서 하나라도 승인하면 승인이 되고 환불을 기다린다")
    void decide_refundAnyApproved_approvedAndRefundPending() {
        DeliveryClaim claim = claim(ClaimType.REFUND, ClaimReason.PRODUCT_DEFECT);

        claim.decide(true);

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(claim.getRefundStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(claim.getProcessedAt()).isNotNull();
    }

    @Test
    @DisplayName("교환을 승인하면 환불이 필요 없다")
    void decide_exchangeAnyApproved_refundNone() {
        DeliveryClaim claim = claim(ClaimType.EXCHANGE, ClaimReason.PRODUCT_DEFECT);

        claim.decide(true);

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(claim.getRefundStatus()).isEqualTo(RefundStatus.NONE);
    }

    @Test
    @DisplayName("모든 상품을 거절하면 거절이 되고 환불이 필요 없다")
    void decide_noneApproved_rejectedAndRefundNone() {
        DeliveryClaim claim = claim(ClaimType.REFUND, ClaimReason.PRODUCT_DEFECT);

        claim.decide(false);

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(claim.getRefundStatus()).isEqualTo(RefundStatus.NONE);
    }

    @Test
    @DisplayName("확인 중인 클레임은 판매자가 결정할 수 없다")
    void decide_verifying_throwsInvalidStatus() {
        DeliveryClaim claim = claim(ClaimType.REFUND, ClaimReason.SIMPLE_CHANGE);

        assertThatThrownBy(() -> claim.decide(true)).isInstanceOf(ServiceException.class)
                .hasMessageContaining("DELIVERY_CLAIM_INVALID_STATUS");
    }

    @Test
    @DisplayName("상품을 승인하면 거절 사유를 남기지 않고, 거절하면 사유를 남긴다")
    void claimItemDecide_rejectKeepsReasonApproveClearsIt() {
        DeliveryClaimItem item = DeliveryClaimItem.create(null, null, 1L);

        item.decide(ClaimItemResult.REJECTED, "사용 흔적이 있어 반품 불가");
        assertThat(item.getResult()).isEqualTo(ClaimItemResult.REJECTED);
        assertThat(item.getRejectReason()).isEqualTo("사용 흔적이 있어 반품 불가");

        item.decide(ClaimItemResult.APPROVED, "무시되는 사유");
        assertThat(item.getResult()).isEqualTo(ClaimItemResult.APPROVED);
        assertThat(item.getRejectReason()).isNull();
    }
}
