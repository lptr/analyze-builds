package com.gradle.enterprise.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Response;
import okhttp3.sse.EventSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

class BuildEventProcessingListener<T> extends PrintFailuresEventSourceListener {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String buildId;
    private final BuildEventProcessor<T> processor;
    private final CompletableFuture<T> result = new CompletableFuture<>();

    public BuildEventProcessingListener(String buildId, BuildEventProcessor<T> processor) {
        this.buildId = buildId;
        this.processor = processor;
    }

    public CompletableFuture<T> getResult() {
        return result;
    }

    @Override
    public void onOpen(@Nonnull EventSource eventSource, @Nonnull Response response) {
        try {
            processor.initialize();
        } catch (Exception e) {
            result.completeExceptionally(new BuildEventProcessingException(buildId, "initialize processing", e));
        }
    }

    @Override
    public void onEvent(@Nonnull EventSource eventSource, @Nullable String id, @Nullable String type, @Nonnull String data) {
        if (!result.isDone()) {
            if ("BuildEvent".equals(type)) {
                try {
                    JsonNode jsonData = MAPPER.readTree(data);
                    processor.process(id, jsonData);
                } catch (Exception e) {
                    result.completeExceptionally(new BuildEventProcessingException(buildId, "process event (" + data + ") for", e));
                }
            }
        }
    }

    @Override
    public void onClosed(@Nonnull EventSource eventSource) {
        if (!result.isDone()) {
            try {
                result.complete(processor.complete());
            } catch (Exception e) {
                result.completeExceptionally(new BuildEventProcessingException(buildId, "complete processing", e));
            }
        }
    }
}
