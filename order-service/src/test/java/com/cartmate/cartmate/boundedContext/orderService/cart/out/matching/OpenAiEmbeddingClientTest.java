package com.cartmate.cartmate.boundedContext.orderService.cart.out.matching;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiEmbeddingClientTest {

    private static final String EMBEDDINGS = OpenAiApi.BASE_URL + "/embeddings";
    private static final String MODEL = "text-embedding-3-small";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(new OpenAiApi(builder, "test-key"), MODEL);

    @Test
    @DisplayName("응답 순서가 섞여 와도 index대로 입력 순서에 맞추고 결과에 모델 이름을 남긴다")
    void ordersByIndex() {
        server.expect(requestTo(EMBEDDINGS)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value(MODEL))
                .andExpect(jsonPath("$.input[1]").value("축구화"))
                .andRespond(withSuccess("{\"data\":[{\"index\":1,\"embedding\":[0.3,0.4]},{\"index\":0,\"embedding\":[0.1,0.2]}]}",
                        MediaType.APPLICATION_JSON));

        ProductEmbeddingClient.Embeddings result = client.embed(List.of("축구공", "축구화"));

        assertThat(result.model()).isEqualTo(MODEL);
        assertThat(result.vectors().get(0)).containsExactly(0.1f, 0.2f);
        assertThat(result.vectors().get(1)).containsExactly(0.3f, 0.4f);
        server.verify();
    }

    @Test
    @DisplayName("응답 벡터 수가 요청 수와 다르면 사용할 수 없다고 알린다")
    void countMismatch() {
        server.expect(requestTo(EMBEDDINGS))
                .andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embed(List.of("축구공", "축구화")))
                .isInstanceOf(OpenAiApi.OpenAiUnavailableException.class);
        server.verify();
    }

    @Test
    @DisplayName("개수는 맞아도 index가 겹치면 순서를 믿을 수 없어 사용할 수 없다고 알린다")
    void duplicatedIndex() {
        server.expect(requestTo(EMBEDDINGS))
                .andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]},{\"index\":0,\"embedding\":[0.3,0.4]}]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embed(List.of("축구공", "축구화")))
                .isInstanceOf(OpenAiApi.OpenAiUnavailableException.class);
        server.verify();
    }

    @Test
    @DisplayName("한도 초과(429)는 다른 모델로 넘기지 않고 사용할 수 없다고 알린다")
    void rateLimited() {
        server.expect(requestTo(EMBEDDINGS)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.embed(List.of("축구공")))
                .isInstanceOf(OpenAiApi.OpenAiUnavailableException.class);
        server.verify();
    }

    @Test
    @DisplayName("키가 없으면 호출하지 않고 설정되지 않았다고 알린다")
    void noKey() {
        OpenAiEmbeddingClient noKey = new OpenAiEmbeddingClient(new OpenAiApi(RestClient.builder(), ""), MODEL);

        assertThatThrownBy(() -> noKey.embed(List.of("축구공")))
                .isInstanceOf(OpenAiApi.OpenAiNotConfiguredException.class);
    }
}
