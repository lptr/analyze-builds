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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.time.Instant.now;

public final class ExportApiJavaExample {

    private static final HttpUrl GRADLE_ENTERPRISE_SERVER_URL = HttpUrl.parse("https://ge.gradle.org");
    private static final String EXPORT_API_ACCESS_KEY = System.getenv("EXPORT_API_ACCESS_KEY");
    private static final int MAX_BUILD_SCANS_STREAMED_CONCURRENTLY = 30;
    private static final String FINISHED = "FINISHED";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Instant since = now().minus(Duration.ofDays(7));

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
        BlockingQueue<String> buildQueue = new ArrayBlockingQueue<>(128);
        FilterBuildsByBuildTool buildToolFilter = new FilterBuildsByBuildTool(eventSourceFactory, buildQueue);
        eventSourceFactory.newEventSource(requestBuilds(since), buildToolFilter);
        BuildStatistics result = Stream.generate(() -> {
                    try {
                        return buildQueue.take();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                })
                .takeWhile(buildId -> buildId != FINISHED)
                .map(buildId -> {
                    FilterBuildByProjectAndTags projectFilter = new FilterBuildByProjectAndTags(buildId);
                    eventSourceFactory.newEventSource(requestBuildInfo(buildId), projectFilter);
                    return projectFilter.getResult();
                })
                .map(future -> future.thenCompose(filterResult -> {
                    if (filterResult.matches) {
                        ProcessBuild buildProcessor = new ProcessBuild(filterResult.buildId);
                        eventSourceFactory.newEventSource(requestBuildEvents(filterResult.buildId), buildProcessor);
                        return buildProcessor.getResult();
                    } else {
                        return CompletableFuture.completedFuture(BuildStatistics.EMPTY);
                    }
                }))
                .map(statsResult -> {
                    try {
                        return statsResult.get(1, TimeUnit.HOURS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .reduce(BuildStatistics::merge)
                .orElse(BuildStatistics.EMPTY);

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

    private static class FilterBuildsByBuildTool extends PrintFailuresEventSourceListener {
        private final EventSource.Factory eventSourceFactory;
        private final BlockingQueue<String> buildQueue;

        private FilterBuildsByBuildTool(EventSource.Factory eventSourceFactory, BlockingQueue<String> buildQueue) {
            this.eventSourceFactory = eventSourceFactory;
            this.buildQueue = buildQueue;
        }

        @Override
        public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
            System.out.println("Streaming builds...");
        }

        @Override
        public void onEvent(@NotNull EventSource eventSource, @Nullable String id, @Nullable String type, @NotNull String data) {
            JsonNode json = parse(data);
            JsonNode buildToolJson = json.get("toolType");
            if (buildToolJson != null && buildToolJson.asText().equals("gradle")) {
                String buildId = json.get("buildId").asText();
                try {
                    buildQueue.put(buildId);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        @Override
        public void onClosed(@NotNull EventSource eventSource) {
            System.out.println("Finished querying builds");
            try {
                buildQueue.put(FINISHED);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class FilterBuildByProjectAndTags extends PrintFailuresEventSourceListener {
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

        private FilterBuildByProjectAndTags(String buildId) {
            this.buildId = buildId;
        }

        public CompletableFuture<Result> getResult() {
            return result;
        }

        @Override
        public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
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
                        rootProjects.add(rootProject);
                        break;
                    case "UserTag":
                        String tag = eventJson.get("data").get("tag").asText();
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
                matches = false;
            } else if (!tags.contains("LOCAL")) {
                matches = false;
            } else {
                System.out.println("Found build " + buildId);
                matches = true;
            }
            result.complete(new Result(buildId, matches));
        }
    }

    private static class ProcessBuild extends PrintFailuresEventSourceListener {
        private final String buildId;
        private final CompletableFuture<BuildStatistics> result = new CompletableFuture<>();
        private final Map<Long, TaskInfo> tasks = new HashMap<>();

        private static class TaskInfo {
            public final String type;
            public final long startTime;
            public long finishTime;

            public TaskInfo(String type, long startTime) {
                this.type = type;
                this.startTime = startTime;
            }
        }

        private ProcessBuild(String buildId) {
            this.buildId = buildId;
        }

        public CompletableFuture<BuildStatistics> getResult() {
            return result;
        }

        @Override
        public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
        }

        @Override
        public void onEvent(@NotNull EventSource eventSource, @Nullable String id, @Nullable String type, @NotNull String data) {
            // System.out.println("Event: " + type + " !! " + id + " - " + data);
            if (type.equals("BuildEvent")) {
                JsonNode eventJson = parse(data);
                long timestamp = eventJson.get("timestamp").asLong();
                String eventType = eventJson.get("type").get("eventType").asText();
                long eventId = eventJson.get("data").get("id").asLong();
                int delta;
                switch (eventType) {
                    case "TaskStarted":
                        tasks.put(eventId, new TaskInfo(
                                eventJson.get("data").get("className").asText(),
                                timestamp
                        ));
                        break;
                    case "TaskFinished":
                        tasks.get(eventId).finishTime = timestamp;
                        break;
                    default:
                        throw new AssertionError("Unknown event type: " + eventType);
                }
            }
        }

        @Override
        public void onClosed(@NotNull EventSource eventSource) {
            System.out.println("Finished processing build " + buildId);
            SortedMap<Long, Integer> startStopEvents = new TreeMap<>();
            AtomicInteger taskCount = new AtomicInteger(0);
            tasks.values().stream()
                    .filter(task -> !task.type.equals("org.gradle.api.tasks.testing.Test"))
                    .forEach(task -> {
                        taskCount.incrementAndGet();
                        startStopEvents.compute(task.startTime, (key, value) -> nullToZero(value) + 1);
                        startStopEvents.compute(task.finishTime, (key, value) -> nullToZero(value) - 1);
                    });

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
            result.complete(new BuildStatistics(1, taskCount.get(), ImmutableSortedMap.copyOfSorted(histogram)));
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
        public static final BuildStatistics EMPTY = new BuildStatistics(0, 0, ImmutableSortedMap.of());

        private final int buildCount;
        private final int taskCount;
        private final ImmutableSortedMap<Integer, Long> histogram;

        public BuildStatistics(int buildCount, int taskCount, ImmutableSortedMap<Integer, Long> histogram) {
            this.buildCount = buildCount;
            this.taskCount = taskCount;
            this.histogram = histogram;
        }

        public void print() {
            if (histogram.isEmpty()) {
                System.out.println("No matching builds found");
                return;
            }
            System.out.println("Statistics for " + buildCount + " builds with " + taskCount + " tasks");
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
            return new BuildStatistics(a.buildCount + b.buildCount, a.taskCount + b.taskCount, merged.build());
        }
    }
}
