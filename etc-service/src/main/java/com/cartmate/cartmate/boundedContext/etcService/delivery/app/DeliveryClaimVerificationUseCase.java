package com.cartmate.cartmate.boundedContext.etcService.delivery.app;

import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.DeliveryClaim;
import com.cartmate.cartmate.boundedContext.etcService.delivery.exception.DeliveryErrorCode;
import com.cartmate.cartmate.boundedContext.etcService.delivery.out.DeliveryClaimRepository;
import com.cartmate.cartmate.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeliveryClaimVerificationUseCase {

    private final DeliveryClaimRepository deliveryClaimRepository;

    // 주문 서버의 접수 검사 결과를 반영한다. 통과하면 접수, 거절되면 이유와 함께 접수 불가로 끝낸다.
    // 확인 중이 아닌 클레임(같은 결과를 다시 받음)은 아무것도 바꾸지 않는다.
    public void apply(Long claimId, boolean accepted, String rejectCode) {
        DeliveryClaim claim = deliveryClaimRepository.findByIdForUpdate(claimId)
                .orElseThrow(() -> new ServiceException(DeliveryErrorCode.DELIVERY_CLAIM_NOT_FOUND));
        if (accepted) {
            claim.accept();
        } else {
            claim.notAccept(rejectCode);
        }
    }
}
