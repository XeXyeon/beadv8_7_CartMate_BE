package com.cartmate.cartmate.boundedContext.orderService.cart.out.matching;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiProductLabelClientTest {

    private static final String CHAT = OpenAiApi.BASE_URL + "/chat/completions";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final OpenAiProductLabelClient client = new OpenAiProductLabelClient(
            new OpenAiApi(builder, "test-key"), List.of("gpt-5-mini", "gpt-4.1-mini"));

    @Test
    @DisplayName("라벨을 상품 번호(no)로 맞춰 입력 순서대로 돌려준다")
    void mapsByNumber() {
        server.expect(requestTo(CHAT))
                .andExpect(jsonPath("$.model").value("gpt-5-mini"))
                .andExpect(jsonPath("$.reasoning_effort").value("minimal"))
                .andExpect(jsonPath("$.temperature").doesNotExist())
                .andRespond(withSuccess(chat("{\\\"labels\\\":[{\\\"no\\\":2,\\\"type\\\":\\\"축구화\\\"},{\\\"no\\\":1,\\\"type\\\":\\\"축구공\\\"}]}"),
                        MediaType.APPLICATION_JSON));

        assertThat(client.labelProductTypes(List.of("나이키 축구공 5호", "푸마 TF 풋살화")))
                .containsExactly("축구공", "축구화");
        server.verify();
    }

    @Test
    @DisplayName("기본 모델이 상품 하나를 빠뜨리면 대체 모델로 묶음 전체를 다시 붙인다")
    void fallbackOnMissingLabel() {
        server.expect(requestTo(CHAT)).andExpect(jsonPath("$.model").value("gpt-5-mini"))
                .andRespond(withSuccess(chat("{\\\"labels\\\":[{\\\"no\\\":1,\\\"type\\\":\\\"축구공\\\"}]}"),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(CHAT))
                .andExpect(jsonPath("$.model").value("gpt-4.1-mini"))
                .andExpect(jsonPath("$.temperature").value(0))
                .andRespond(withSuccess(chat("{\\\"labels\\\":[{\\\"no\\\":1,\\\"type\\\":\\\"축구공\\\"},{\\\"no\\\":2,\\\"type\\\":\\\"축구화\\\"}]}"),
                        MediaType.APPLICATION_JSON));

        assertThat(client.labelProductTypes(List.of("나이키 축구공 5호", "푸마 TF 풋살화")))
                .containsExactly("축구공", "축구화");
        server.verify();
    }

    @Test
    @DisplayName("번호가 겹치거나 범위 밖이라 빠진 상품이 생기면 대체 모델로 넘긴다")
    void fallbackOnDuplicatedOrOutOfRangeNumber() {
        server.expect(requestTo(CHAT)).andExpect(jsonPath("$.model").value("gpt-5-mini"))
                .andRespond(withSuccess(chat("{\\\"labels\\\":[{\\\"no\\\":1,\\\"type\\\":\\\"축구공\\\"},{\\\"no\\\":1,\\\"type\\\":\\\"축구화\\\"},{\\\"no\\\":3,\\\"type\\\":\\\"축구화\\\"}]}"),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(CHAT)).andExpect(jsonPath("$.model").value("gpt-4.1-mini"))
                .andRespond(withSuccess(chat("{\\\"labels\\\":[{\\\"no\\\":1,\\\"type\\\":\\\"축구공\\\"},{\\\"no\\\":2,\\\"type\\\":\\\"축구화\\\"}]}"),
                        MediaType.APPLICATION_JSON));

        assertThat(client.labelProductTypes(List.of("나이키 축구공 5호", "푸마 TF 풋살화")))
                .containsExactly("축구공", "축구화");
        server.verify();
    }

    @Test
    @DisplayName("거부 응답처럼 content가 없거나, 모델 이름 오류(400)여도 대체 모델로 넘긴다")
    void fallbackOnRefusalAndRejectedModel() {
        server.expect(requestTo(CHAT)).andExpect(jsonPath("$.model").value("gpt-5-mini"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        server.expect(requestTo(CHAT)).andExpect(jsonPath("$.model").value("gpt-4.1-mini"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":null,\"refusal\":\"no\"}}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.labelProductTypes(List.of("나이키 축구공 5호"))).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("모든 모델이 실패하면 빈 목록을 돌려줘 규칙 텍스트로 넘어가게 한다")
    void allModelsFail() {
        server.expect(requestTo(CHAT)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(CHAT)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(client.labelProductTypes(List.of("나이키 축구공 5호"))).isEmpty();
        server.verify();
    }

    private static String chat(String escapedContent) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + escapedContent + "\"}}]}";
    }
}
