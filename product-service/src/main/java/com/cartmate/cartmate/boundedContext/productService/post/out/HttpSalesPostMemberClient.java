package com.cartmate.cartmate.boundedContext.productService.post.out;

import com.cartmate.cartmate.boundedContext.productService.code.SalesPostErrorCode;
import com.cartmate.cartmate.global.exception.ServiceException;
import com.cartmate.cartmate.global.http.InternalApiClient;
import com.cartmate.cartmate.global.http.InternalApiClient.InternalResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
class HttpSalesPostMemberClient implements SalesPostMemberClient {

    static final String PATH = "/internal/members/nicknames?ids={ids}";
    static final ParameterizedTypeReference<InternalResponse<Map<Long, String>>> NICKNAMES =
            new ParameterizedTypeReference<>() {};

    // 탈퇴 완료 회원 ID: from 이상 ~ to 미만 (from이 없으면 처음부터). 시각은 ISO 형식(2026-11-18T00:00:00)
    static final String WITHDRAWN_PATH = "/internal/members/withdrawn?from={from}&to={to}";
    static final String WITHDRAWN_FROM_START_PATH = "/internal/members/withdrawn?to={to}";
    static final ParameterizedTypeReference<InternalResponse<List<Long>>> MEMBER_IDS =
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

    @Override
    public List<Long> findWithdrawnMemberIds(LocalDateTime from, LocalDateTime to) {
        List<Long> memberIds = from == null
                ? memberServerApiClient.get(WITHDRAWN_FROM_START_PATH, MEMBER_IDS, format(to))
                : memberServerApiClient.get(WITHDRAWN_PATH, MEMBER_IDS, format(from), format(to));
        return memberIds == null ? List.of() : memberIds;
    }

    private static String format(LocalDateTime time) {
        return time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
