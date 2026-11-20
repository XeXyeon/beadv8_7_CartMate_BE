package com.cartmate.cartmate.boundedContext.orderService.cart.out.matching;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
class OpenAiEmbeddingClient implements ProductEmbeddingClient {

    private static final int MAX_BATCH = 100;

    private final OpenAiApi openAiApi;
    private final String model;

    OpenAiEmbeddingClient(OpenAiApi openAiApi,
                          @Value("${custom.matching.openai.embedding-model:text-embedding-3-small}") String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("embedding model is required");
        }
        this.openAiApi = openAiApi;
        this.model = model;
    }

    @Override
    public Embeddings embed(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += MAX_BATCH) {
            List<String> chunk = texts.subList(from, Math.min(texts.size(), from + MAX_BATCH));
            Map<?, ?> response = openAiApi.post("/embeddings", Map.of("model", model, "input", chunk));
            vectors.addAll(parse(response, chunk.size()));
        }
        return new Embeddings(model, vectors);
    }

    private List<float[]> parse(Map<?, ?> response, int expected) {
        if (!(response.get("data") instanceof List<?> data) || data.size() != expected) {
            throw new OpenAiApi.OpenAiUnavailableException(
                    "OpenAI " + model + " returned malformed embeddings for " + expected + " texts", null);
        }
        List<Map<?, ?>> items = new ArrayList<>(expected);
        for (Object item : data) {
            if (!(item instanceof Map<?, ?> map) || !(map.get("index") instanceof Number)
                    || !(map.get("embedding") instanceof List<?> values) || values.isEmpty()) {
                throw new OpenAiApi.OpenAiUnavailableException("OpenAI " + model + " returned an empty embedding", null);
            }
            items.add(map);
        }
        items.sort(Comparator.comparingInt(map -> ((Number) map.get("index")).intValue()));
        List<float[]> vectors = new ArrayList<>(expected);
        for (int i = 0; i < expected; i++) {
            Map<?, ?> item = items.get(i);
            if (((Number) item.get("index")).intValue() != i) {
                throw new OpenAiApi.OpenAiUnavailableException("OpenAI " + model + " returned embeddings out of range", null);
            }
            List<?> values = (List<?>) item.get("embedding");
            if (!vectors.isEmpty() && values.size() != vectors.getFirst().length) {
                throw new OpenAiApi.OpenAiUnavailableException("OpenAI " + model + " returned embeddings of mixed dimensions", null);
            }
            float[] vector = new float[values.size()];
            for (int j = 0; j < vector.length; j++) {
                if (!(values.get(j) instanceof Number number)) {
                    throw new OpenAiApi.OpenAiUnavailableException("OpenAI " + model + " returned a non-numeric embedding", null);
                }
                vector[j] = number.floatValue();
            }
            vectors.add(vector);
        }
        return vectors;
    }

    @Override
    public String primaryModel() {
        return model;
    }
}
