package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.http.InternalApiClient;
import com.cartmate.cartmate.global.http.InternalApiClient.InternalResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
class HttpSalesPostMemberClient implements SalesPostMemberClient {

    static final String PATH = "/internal/members/nicknames?ids={ids}";
    static final ParameterizedTypeReference<InternalResponse<Map<Long, String>>> NICKNAMES =
            new ParameterizedTypeReference<>() {};

    private final InternalApiClient memberServerApiClient;

    @Override
    public String findNickname(Long memberId) {
        Map<Long, String> nicknames = memberServerApiClient.get(PATH, NICKNAMES, String.valueOf(memberId));
        String nickname = nicknames == null ? null : nicknames.get(memberId);
        if (nickname == null || nickname.isBlank()) {
            throw new ServiceException(SalesPostErrorCode.COMMENT_WRITER_NOT_FOUND);
        }
        return nickname;
    }
}
