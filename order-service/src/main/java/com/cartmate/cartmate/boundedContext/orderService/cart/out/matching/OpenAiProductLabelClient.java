package com.cartmate.cartmate.boundedContext.orderService.cart.out.matching;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Component
class OpenAiProductLabelClient implements ProductLabelClient {

    private static final String PROMPT = """
            각 상품의 '상품 종류'를 공동구매로 묶을 수 있는 수준의 짧은 한국어 명사구로 써라.
            브랜드, 모델명, 크기, 용량, 연령(성인/아동), 색상, 홍보 문구는 빼라.
            단, 구단 · 국가대표 유니폼은 구단 · 팀 이름을 남겨라(예: "레알 마드리드 유니폼").
            풋살공과 축구공은 다른 종류다. 축구화는 바닥 종류(FG · TF · AG · HG · IC)와 상관없이 "축구화"로 쓴다. 풋살화 · 터프화 · 실내화도 "축구화"로 쓴다.
            labels에 상품마다 하나씩, 상품 번호(no)와 종류(type)를 써라.
            상품:
            %s""";

    private static final Map<String, Object> RESPONSE_FORMAT = Map.of(
            "type", "json_schema",
            "json_schema", Map.of(
                    "name", "product_types",
                    "strict", true,
                    "schema", Map.of(
                            "type", "object",
                            "properties", Map.of("labels", Map.of(
                                    "type", "array",
                                    "items", Map.of(
                                            "type", "object",
                                            "properties", Map.of(
                                                    "no", Map.of("type", "integer"),
                                                    "type", Map.of("type", "string")),
                                            "required", List.of("no", "type"),
                                            "additionalProperties", false))),
                            "required", List.of("labels"),
                            "additionalProperties", false)));

    private final OpenAiApi openAiApi;
    private final List<String> models;
    private final ObjectMapper objectMapper = new ObjectMapper();

    OpenAiProductLabelClient(OpenAiApi openAiApi,
                             @Value("${custom.matching.openai.label-models:gpt-5-mini,gpt-4.1-mini}")
                             List<String> models) {
        this.openAiApi = openAiApi;
        this.models = List.copyOf(models);
    }

    @Override
    public List<String> labelProductTypes(List<String> productNames) {
        if (productNames.isEmpty() || !openAiApi.isConfigured()) {
            return List.of();
        }
        String listing = IntStream.range(0, productNames.size())
                .mapToObj(i -> (i + 1) + ". " + productNames.get(i))
                .collect(Collectors.joining("\n"));
        for (String model : models) {
            try {
                Map<?, ?> response = openAiApi.post("/chat/completions", body(model, PROMPT.formatted(listing)));
                List<String> labels = parse(response, productNames.size());
                if (!labels.isEmpty()) {
                    return labels;
                }
                log.warn("OpenAI {} returned incomplete labels for {} products", model, productNames.size());
            } catch (RestClientResponseException e) {
                log.error("OpenAI {} rejected labeling with status {}; check custom.matching.openai.label-models",
                        model, e.getStatusCode().value());
            } catch (RuntimeException e) {
                log.warn("OpenAI {} failed to label {} products: {}", model, productNames.size(), e.getMessage());
            }
        }
        return List.of();
    }

    private static Map<String, Object> body(String model, String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("response_format", RESPONSE_FORMAT);
        if (model.startsWith("gpt-5")) {
            body.put("reasoning_effort", "minimal");
        } else {
            body.put("temperature", 0);
        }
        return body;
    }

    private List<String> parse(Map<?, ?> response, int expected) {
        try {
            Map<?, ?> choice = (Map<?, ?>) ((List<?>) response.get("choices")).get(0);
            String content = (String) ((Map<?, ?>) choice.get("message")).get("content");
            Map<?, ?> parsed = objectMapper.readValue(content, Map.class);
            Map<Integer, String> byNo = new HashMap<>();
            for (Object item : (List<?>) parsed.get("labels")) {
                Map<?, ?> label = (Map<?, ?>) item;
                byNo.putIfAbsent(((Number) label.get("no")).intValue(), (String) label.get("type"));
            }
            List<String> labels = new ArrayList<>(expected);
            for (int no = 1; no <= expected; no++) {
                String label = byNo.get(no);
                if (label == null || label.isBlank()) {
                    return List.of();
                }
                labels.add(label);
            }
            return labels;
        } catch (Exception e) {
            return List.of();
        }
    }
}
