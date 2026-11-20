package com.cartmate.cartmate.boundedContext.orderService.cart.out.matching;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

@Slf4j
@Component
class OpenAiApi {

    static final String BASE_URL = "https://api.openai.com/v1";

    private final RestClient restClient;
    private final String apiKey;

    @Autowired
    OpenAiApi(@Value("${OPENAI_API_KEY:}") String apiKey) {
        this(RestClient.builder().requestFactory(requestFactory()), apiKey);
        if (!isConfigured()) {
            log.warn("OPENAI_API_KEY is not set; product embedding and labeling are disabled");
        }
    }

    OpenAiApi(RestClient.Builder builder, String apiKey) {
        this.restClient = builder.baseUrl(BASE_URL).build();
        this.apiKey = apiKey;
    }

    boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    Map<?, ?> post(String path, Object body) {
        if (!isConfigured()) {
            throw new OpenAiNotConfiguredException();
        }
        Map<?, ?> response;
        try {
            response = restClient.post()
                    .uri(path)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException e) {
            if (isTransient(e.getStatusCode().value())) {
                throw new OpenAiUnavailableException("OpenAI " + e.getStatusCode().value() + " on " + path, e);
            }
            throw e;
        } catch (ResourceAccessException e) {
            throw new OpenAiUnavailableException("OpenAI unreachable on " + path, e);
        }
        if (response == null) {
            throw new OpenAiUnavailableException("OpenAI empty body on " + path, null);
        }
        return response;
    }

    private static boolean isTransient(int status) {
        return status == HttpStatus.TOO_MANY_REQUESTS.value() || status >= 500;
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        return factory;
    }

    static class OpenAiUnavailableException extends RuntimeException {

        OpenAiUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static class OpenAiNotConfiguredException extends RuntimeException {

        OpenAiNotConfiguredException() {
            super("OPENAI_API_KEY is not set");
        }
    }
}
