package com.cartmate.cartmate.boundedContext.paymentService.wallet.app;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 지갑이 없는 회원의 지갑을 그 자리에서 만든다 (get-or-create의 create).
 *
 * <p>지갑은 원래 가입 이벤트(Kafka)로 만들어진다. 그 이벤트가 끝내 처리되지 못하면(DLT, Kafka를 끈 채 가입한 회원 등)
 * 지갑이 없어 충전·조회·환급·정산이 모두 "지갑을 찾을 수 없습니다"로 막힌다. 지갑이 필요한 곳에서 없으면 여기서 만든다.
 *
 * <p>별도 트랜잭션(REQUIRES_NEW)으로 만들고 바로 커밋한다. 같은 회원으로 동시에 들어와 둘 다 만들려 하면
 * member_id 유니크에 하나가 걸리는데, 그건 "다른 요청이 먼저 만들었다"는 뜻이라 성공으로 본다 → 몇 번 불러도 지갑은 하나.
 */
@Slf4j
@Service
public class WalletEnsureUseCase {

    private final WalletCreateUseCase walletCreateUseCase;
    private final TransactionTemplate requiresNew;

    public WalletEnsureUseCase(WalletCreateUseCase walletCreateUseCase, PlatformTransactionManager transactionManager) {
        this.walletCreateUseCase = walletCreateUseCase;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void ensure(Long memberId) {
        try {
            Boolean created = requiresNew.execute(status -> walletCreateUseCase.createWallet(memberId));
            if (!Boolean.TRUE.equals(created)) {
                return; // 그사이 다른 요청이 만들었다
            }
            log.atWarn().addKeyValue("event", "wallet.created-on-demand")
                    .addKeyValue("memberId", memberId)
                    .log("지갑이 없어 그 자리에서 만들었다 (가입 이벤트가 처리되지 않은 회원): memberId={}", memberId);
        } catch (DataIntegrityViolationException e) {
            // 동시에 들어온 다른 요청이 먼저 만들었다 → 이미 있으므로 성공
        }
    }
}
