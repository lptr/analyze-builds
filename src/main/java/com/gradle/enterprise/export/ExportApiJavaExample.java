package com.gradle.enterprise.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Sets;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.time.Instant.now;

public final class ExportApiJavaExample {

    private static final HttpUrl GRADLE_ENTERPRISE_SERVER_URL = HttpUrl.parse("https://ge.gradle.org");
    private static final String EXPORT_API_ACCESS_KEY = System.getenv("EXPORT_API_ACCESS_KEY");
    private static final int MAX_BUILD_SCANS_STREAMED_CONCURRENTLY = 30;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Instant since = now().minus(Duration.ofMinutes(300));

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

        result.print();

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
    private static Request requestBuildInfo(String buildId) {
        return new Request.Builder()
                .url(GRADLE_ENTERPRISE_SERVER_URL.resolve("/build-export/v2/build/" + buildId + "/events?eventTypes=ProjectStructure,UserTag"))
                .build();
    }

    @NotNull
    private static Request requestBuildEvents(String buildId) {
        return new Request.Builder()
                .url(GRADLE_ENTERPRISE_SERVER_URL.resolve("/build-export/v2/build/" + buildId + "/events?eventTypes=TaskStarted,TaskFinished"))
                .build();
    }

    private static class QueryBuilds extends PrintFailuresEventSourceListener {
        private final List<CompletableFuture<FilterBuild.Result>> builds = new ArrayList<>();
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

                Request request = requestBuildInfo(buildId);
                FilterBuild listener = new FilterBuild(buildId);
                eventSourceFactory.newEventSource(request, listener);
                builds.add(listener.getResult());
            }
        }

        @Override
        public void onClosed(@NotNull EventSource eventSource) {
            BuildStatistics stats = builds.stream()
                    .map((CompletableFuture<FilterBuild.Result> build) -> build.thenCompose(this::getBuildInfo))
                    .reduce((left, right) -> left.thenCombine(right, BuildStatistics::merge))
                    .map(result -> {
                        try {
                            return result.get(20, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .orElse(BuildStatistics.EMPTY);
            result.complete(stats);
        }

        private CompletableFuture<BuildStatistics> getBuildInfo(FilterBuild.Result result) {
            if (result.matches) {
                Request request = requestBuildEvents(result.buildId);
                ProcessBuild listener = new ProcessBuild(result.buildId);
                eventSourceFactory.newEventSource(request, listener);
                return listener.getResult();
            } else {
                return CompletableFuture.completedFuture(BuildStatistics.EMPTY);
            }
        }
    }

    private static class FilterBuild extends PrintFailuresEventSourceListener {
        public static class Result {
            public final String buildId;
            public final boolean matches;

            public Result(String buildId, boolean matches) {
                this.buildId = buildId;
                this.matches = matches;
            }
        }

        private final String buildId;
        private final CompletableFuture<Result> result = new CompletableFuture<>();
        private final List<String> rootProjects = new ArrayList<>();
        private final List<String> tags = new ArrayList<>();

        private FilterBuild(String buildId) {
            this.buildId = buildId;
        }

        public CompletableFuture<Result> getResult() {
            return result;
        }

        @Override
        public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
            System.out.println("Filtering : " + buildId);
        }

        @Override
        public void onEvent(@NotNull EventSource eventSource, @Nullable String id, @Nullable String type, @NotNull String data) {
            // System.out.println("Event: " + type + " - " + data);
            if (type.equals("BuildEvent")) {
                JsonNode eventJson = parse(data);
                long timestamp = eventJson.get("timestamp").asLong();
                String eventType = eventJson.get("type").get("eventType").asText();
                int delta;
                switch (eventType) {
                    case "ProjectStructure":
                        String rootProject = eventJson.get("data").get("rootProjectName").asText();
//                        System.out.println("Found root project " + rootProject);
                        rootProjects.add(rootProject);
                        break;
                    case "UserTag":
                        String tag = eventJson.get("data").get("tag").asText();
//                        System.out.println("Found tag " + tag);
                        tags.add(tag);
                        break;
                    default:
                        throw new AssertionError("Unknown event type: " + eventType);
                }

            }
        }

        @Override
        public void onClosed(@NotNull EventSource eventSource) {
            boolean matches;
            BuildStatistics stats;
            if (!rootProjects.contains("gradle")) {
                System.out.println("Couldn't find 'gradle' among root projects, skipping");
                matches = false;
            } else if (!tags.contains("LOCAL")) {
                System.out.println("Local tag not found, skipping");
                matches = false;
            } else {
                matches = true;
            }
            result.complete(new Result(buildId, matches));
        }
    }


    private static class ProcessBuild extends PrintFailuresEventSourceListener {
        private final String buildId;
        private final SortedMap<Long, Integer> startStopEvents = new TreeMap<>();
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
            // System.out.println("Event: " + type + " - " + data);
            if (type.equals("BuildEvent")) {
                JsonNode eventJson = parse(data);
                long timestamp = eventJson.get("timestamp").asLong();
                String eventType = eventJson.get("type").get("eventType").asText();
                int delta;
                switch (eventType) {
                    case "TaskStarted":
                        startStopEvents.compute(timestamp, (key, value) -> nullToZero(value) + 1);
                        break;
                    case "TaskFinished":
                        startStopEvents.compute(timestamp, (key, value) -> nullToZero(value) - 1);
                        break;
                    default:
                        throw new AssertionError("Unknown event type: " + eventType);
                }

            }
        }

        @Override
        public void onClosed(@NotNull EventSource eventSource) {
            int concurrencyLevel = 0;
            long lastTimeStamp = 0;
            SortedMap<Integer, Long> histogram = new TreeMap<>();
            for (Map.Entry<Long, Integer> entry : startStopEvents.entrySet()) {
                long timestamp = entry.getKey();
                int delta = entry.getValue();
                if (concurrencyLevel != 0) {
                    long duration = timestamp - lastTimeStamp;
                    histogram.compute(concurrencyLevel, (concurrency, recordedDuration) -> (recordedDuration == null ? 0 : recordedDuration) + duration);
                }
                concurrencyLevel += delta;
                lastTimeStamp = timestamp;
            }
            result.complete(new BuildStatistics(1, ImmutableSortedMap.copyOfSorted(histogram)));
        }
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
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
        public static final BuildStatistics EMPTY = new BuildStatistics(0, ImmutableSortedMap.of());

        private int buildCount;
        private ImmutableSortedMap<Integer, Long> histogram;

        public BuildStatistics(int buildCount, ImmutableSortedMap<Integer, Long> histogram) {
            this.buildCount = buildCount;
            this.histogram = histogram;
        }

        public void print() {
            if (histogram.isEmpty()) {
                System.out.println("No matching builds found");
                return;
            }
            System.out.println("Statistics for " + buildCount + " builds");
            int maxConcurrencyLevel = histogram.lastKey();
            for (int concurrencyLevel = maxConcurrencyLevel; concurrencyLevel >= 1; concurrencyLevel--) {
                System.out.println(concurrencyLevel + ": " + histogram.getOrDefault(concurrencyLevel, 0L) + " ms");
            }
        }

        public static BuildStatistics merge(BuildStatistics a, BuildStatistics b) {
            ImmutableSortedMap.Builder<Integer, Long> merged = ImmutableSortedMap.naturalOrder();
            for (Integer concurrencyLevel : Sets.union(a.histogram.keySet(), b.histogram.keySet())) {
                merged.put(concurrencyLevel, a.histogram.getOrDefault(concurrencyLevel, 0L) + b.histogram.getOrDefault(concurrencyLevel, 0L));
            }
            return new BuildStatistics(a.buildCount + b.buildCount, merged.build());
        }
    }
}
