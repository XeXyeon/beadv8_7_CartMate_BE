package com.cartmate.cartmate.boundedContext.etcService.delivery.app;

import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.DeliveryClaimCreateCommand;
import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.DeliveryClaimDecideRequest;
import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.DeliveryClaimResponse;
import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.DeliveryOrderRef;
import com.cartmate.cartmate.boundedContext.etcService.delivery.app.dto.SellerClaimResponse;
import com.cartmate.cartmate.boundedContext.etcService.delivery.domain.ClaimType;
import com.cartmate.cartmate.global.lock.DistributedLockExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

// 클레임 처리·완료를 배송 키 분산락 안에서 실행하고, 반품 접수는 그 바깥에서 주문 키 분산락으로 한 번 더 줄을 세운다.
// 락 키에 배송 ID가 필요해서 먼저 클레임의 배송 ID를 읽고(락 밖, 짧은 읽기 트랜잭션), 락을 잡은 뒤 처리 트랜잭션을 연다.
// 클레임의 배송은 바뀌지 않으므로 락 전에 읽은 배송 ID로 키를 만들어도 된다. 상태 판단은 락 안의 트랜잭션에서 다시 읽어서 한다.
// 이 클래스에는 트랜잭션을 걸지 않는다. 걸면 락이 트랜잭션 안쪽에서 풀려 커밋 전에 다른 요청이 들어온다.
@Service
@RequiredArgsConstructor
public class DeliveryClaimLockService {

    // 다른 서비스의 락 키와 겹치지 않게 클레임 접수용 접두어를 따로 쓴다
    private static final String ORDER_LOCK_KEY_PREFIX = "claim-order:";

    private final DeliveryFacade deliveryFacade;
    private final DistributedLockExecutor lockExecutor;

    // 반품 접수 검사는 같은 주문에서 처리 중인 다른 반품을 먼저 환불된 것으로 보고 계산한다.
    // 서로 다른 배송의 반품이 동시에 들어오면 서로를 못 보고 둘 다 통과할 수 있어서, 같은 주문의 반품 접수를 순서대로 처리한다.
    // 항상 주문 락을 먼저, 배송 락(createClaim 안)을 나중에 잡는다. 처리·완료·구매확정은 배송 락만 잡으므로 서로 기다리지 않는다.
    // 교환은 환불 계산에 영향을 주지 않아 주문 락 없이 접수한다.
    public DeliveryClaimResponse create(DeliveryClaimCreateCommand command) {
        if (command.deliveryId() == null || command.claimType() != ClaimType.REFUND) {
            return deliveryFacade.createClaim(command);
        }
        DeliveryOrderRef order = deliveryFacade.getDeliveryOrderRef(command.deliveryId());
        return lockExecutor.get(ORDER_LOCK_KEY_PREFIX + order.purchaseType() + ":" + order.orderId(),
                () -> deliveryFacade.createClaim(command));
    }

    public SellerClaimResponse decide(Long claimId, Long memberId, List<DeliveryClaimDecideRequest.Item> decisions) {
        return lockExecutor.get(lockKey(claimId), () -> deliveryFacade.decideClaim(claimId, memberId, decisions));
    }

    public SellerClaimResponse complete(Long claimId, Long memberId) {
        return lockExecutor.get(lockKey(claimId), () -> deliveryFacade.completeClaim(claimId, memberId));
    }

    private String lockKey(Long claimId) {
        return DeliveryFacade.LOCK_KEY_PREFIX + deliveryFacade.getClaimDeliveryId(claimId);
    }
}
