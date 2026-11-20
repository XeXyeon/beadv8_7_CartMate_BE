package com.cartmate.cartmate.boundedContext.paymentService.wallet.out;

import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.http.InternalApiClient;
import com.cartmate.cartmate.global.http.InternalApiClient.InternalResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.Optional;

// 회원 서버 내부 API: 출금 계좌 (원문). 출금 신청 때 트랜잭션 밖에서 한 번 부른다
@Component
@RequiredArgsConstructor
public class PayoutAccountClient {

    private final InternalApiClient memberServerApiClient; // 빈 이름으로 고른다 (InternalApiClientConfig)

    // 등록된 계좌가 없으면(404) 빈 값
    public Optional<PayoutAccount> find(Long memberId) {
        try {
            return Optional.ofNullable(memberServerApiClient.get("/internal/members/{m}/payout-account",
                    new ParameterizedTypeReference<InternalResponse<PayoutAccount>>() {}, memberId));
        } catch (ServiceException e) {
            if (InternalApiClient.isNotFound(e)) {
                return Optional.empty();
            }
            throw e;
        }
    }

    // 회원 서버 응답(PayoutAccountInternalResponse)과 필드 이름을 맞춘다
    public record PayoutAccount(String bankCode, String bankName, String accountHolder, String accountNumber) {
    }
}
