package com.gradle.enterprise.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;
import okhttp3.ConnectionPool;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.checkerframework.checker.units.qual.C;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static java.time.Instant.now;

public final class ExportApiJavaExample {

    private static final HttpUrl GRADLE_ENTERPRISE_SERVER_URL = HttpUrl.parse("https://ge.gradle.org");
    private static final String EXPORT_API_ACCESS_KEY = System.getenv("EXPORT_API_ACCESS_KEY");
    private static final int MAX_BUILD_SCANS_STREAMED_CONCURRENTLY = 30;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Instant since = now().minus(Duration.ofMinutes(5));

        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ZERO)
                .readTimeout(Duration.ZERO)
                .retryOnConnectionFailure(true)
                .connectionPool(new ConnectionPool(MAX_BUILD_SCANS_STREAMED_CONCURRENTLY, 30, TimeUnit.SECONDS))
                .authenticator(Authenticators.bearerToken(EXPORT_API_ACCESS_KEY))
                .protocols(ImmutableList.of(Protocol.HTTP_1_1))
                .build();
        httpClient.dispatcher().setMaxRequests(MAX_BUILD_SCANS_STREAMED_CONCURRENTLY);
        httpClient.dispatcher().setMaxRequestsPerHost(MAX_BUILD_SCANS_STREAMED_CONCURRENTLY);

        EventSource.Factory eventSourceFactory = EventSources.createFactory(httpClient);
        QueryBuilds listener = new QueryBuilds(eventSourceFactory);

        eventSourceFactory.newEventSource(requestBuilds(since), listener);
        BuildStatistics result = listener.getResult().get();

        System.out.println("Results:");

        // Cleanly shuts down the HTTP client, which speeds up process termination
        shutdown(httpClient);
    }

    @NotNull
    private static Request requestBuilds(Instant since) {
        return new Request.Builder()
                .url(GRADLE_ENTERPRISE_SERVER_URL.resolve("/build-export/v2/builds/since/" + since.toEpochMilli()))
                .build();
    }

    @NotNull
    private static Request requestBuildEvents(String buildId) {
        return new Request.Builder()
                .url(GRADLE_ENTERPRISE_SERVER_URL.resolve("/build-export/v2/build/" + buildId + "/events?eventTypes=TaskStarted,TaskFinished"))
                .build();
    }

    private static class QueryBuilds extends PrintFailuresEventSourceListener {
        private final List<CompletableFuture<BuildStatistics>> buildsBeingProcessed = new ArrayList<>();
        private final CompletableFuture<BuildStatistics> result = new CompletableFuture<>();
        private final EventSource.Factory eventSourceFactory;

        private QueryBuilds(EventSource.Factory eventSourceFactory) {
            this.eventSourceFactory = eventSourceFactory;
        }

        public CompletableFuture<BuildStatistics> getResult() {
            return result;
        }

        @Override
        public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
            System.out.println("Streaming builds...");
        }

        @Override
        public void onEvent(@NotNull EventSource eventSource, @Nullable String id, @Nullable String type, @NotNull String data) {
            JsonNode json = parse(data);
            System.out.println("Found build " + json.get("buildId").asText() + " - " + data);
            JsonNode buildToolJson = json.get("toolType");
            if (buildToolJson != null && buildToolJson.asText().equals("gradle")) {
                final String buildId = json.get("buildId").asText();

                Request request = requestBuildEvents(buildId);

                ProcessBuild listener = new ProcessBuild(buildId);
                eventSourceFactory.newEventSource(request, listener);
                buildsBeingProcessed.add(listener.getResult());
            }
        }

        @Override
        public void onClosed(@NotNull EventSource eventSource) {
            result.complete(buildsBeingProcessed.stream()
                    .map(result -> {
                        try {
                            return result.get(5, TimeUnit.SECONDS);
                        } catch (InterruptedException | ExecutionException | TimeoutException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .reduce((a, b) -> a)
                    .get());
        }
    }

    private static class ProcessBuild extends PrintFailuresEventSourceListener {
        private final String buildId;
        private final CompletableFuture<BuildStatistics> result = new CompletableFuture<>();

        private ProcessBuild(String buildId) {
            this.buildId = buildId;
        }

        public CompletableFuture<BuildStatistics> getResult() {
            return result;
        }

        @Override
        public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
            System.out.println("Streaming events for : " + buildId);
        }

        @Override
        public void onEvent(@NotNull EventSource eventSource, @Nullable String id, @Nullable String type, @NotNull String data) {
            System.out.println("Event: " + type + " - " + data);
            if (type.equals("BuildEvent")) {
                JsonNode eventJson = parse(data);

            }
        }

        @Override
        public void onClosed(@NotNull EventSource eventSource) {
            System.out.println("Finished processing build " + buildId);
            result.complete(new BuildStatistics());
        }
    }

    private static class PrintFailuresEventSourceListener extends EventSourceListener {
        @Override
        public void onFailure(@NotNull EventSource eventSource, @Nullable Throwable t, @Nullable Response response) {
            if (t != null) {
                System.err.println("FAILED: " + t.getMessage());
                t.printStackTrace();
            }
            if (response != null) {
                System.err.println("Bad response: " + response);
                System.err.println("Response body: " + getResponseBody(response));
            }
            eventSource.cancel();
            this.onClosed(eventSource);
        }

        private String getResponseBody(Response response) {
            try {
                return response.body().string();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static JsonNode parse(String data) {
        try {
            return MAPPER.readTree(data);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static void shutdown(OkHttpClient httpClient) {
        httpClient.dispatcher().cancelAll();
        MoreExecutors.shutdownAndAwaitTermination(httpClient.dispatcher().executorService(), Duration.ofSeconds(10));
    }

    private static class BuildStatistics {
    }
}
