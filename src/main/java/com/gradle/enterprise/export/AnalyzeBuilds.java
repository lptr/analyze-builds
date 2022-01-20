package com.gradle.enterprise.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ForwardingBlockingQueue;
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
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableSortedMap.copyOfSorted;
import static java.time.Instant.now;

@Command(
        name = "analyze",
        description = "Analyze GE data",
        mixinStandardHelpOptions = true
)
public final class AnalyzeBuilds implements Callable<Integer> {
    @Option(names = "--server", description = "GE server URL", converter = HttpUrlConverter.class)
    private HttpUrl serverUrl = HttpUrl.parse("https://ge.gradle.org");

    @Option(names = "--api-key", description = "Export API access key, can be set via EXPORT_API_ACCESS_KEY environment variable")
    private String exportApiAccessKey = System.getenv("EXPORT_API_ACCESS_KEY");

    @Option(names = "--projects", split = ",")
    private List<String> projectNames = Arrays.asList("gradle");

    @Option(names = "--max-concurrency", description = "Maximum number of build scans streamed concurrently")
    private int maxBuildScansStreamedConcurrently = 30;

    @Option(names = "--load-from", description = "File to load build IDs from")
    private File buildInputFile;

    @Option(names = "--save-to", description = "File to save build IDs to")
    private File buildOutputFile;

    @Option(names = "--query-since", description = "Query builds in the given timeframe; defaults to two hours, see Duration.parse() for more info; ignored when --load-from is specified", converter = DurationConverter.class)
    private Duration since = Duration.ofHours(2);

    @Option(names = "--exclude-task-type", description = "Exclude tasks with FQCNs starting with the given pattern")
    private List<String> excludedTaskTypes = ImmutableList.of();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        int exitCode = new CommandLine(new AnalyzeBuilds()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ZERO)
                .readTimeout(Duration.ZERO)
                .retryOnConnectionFailure(true)
                .connectionPool(new ConnectionPool(maxBuildScansStreamedConcurrently, 30, TimeUnit.SECONDS))
                .authenticator(Authenticators.bearerToken(exportApiAccessKey))
                .protocols(ImmutableList.of(Protocol.HTTP_1_1))
                .build();
        httpClient.dispatcher().setMaxRequests(maxBuildScansStreamedConcurrently);
        httpClient.dispatcher().setMaxRequestsPerHost(maxBuildScansStreamedConcurrently);

        System.out.printf("Connecting to GE server at %s, fetching info about projects: %s%n", serverUrl, projectNames.stream().collect(Collectors.joining(", ")));

        EventSource.Factory eventSourceFactory = EventSources.createFactory(httpClient);
        Stream<String> builds = buildInputFile == null
                ? queryBuildsFromPast(since, eventSourceFactory)
                : loadBuildsFromFile(buildInputFile);
        BuildStatistics result = builds
                .parallel()
                .map(buildId -> {
                    ProcessBuildInfo projectFilter = new ProcessBuildInfo(buildId, projectNames);
                    eventSourceFactory.newEventSource(requestBuildInfo(buildId), projectFilter);
                    return projectFilter.getResult();
                })
                .map(future -> future.thenCompose(filterResult -> {
                    if (filterResult.matches) {
                        ProcessTaskEvents buildProcessor = new ProcessTaskEvents(filterResult.buildId, filterResult.maxWorkers, excludedTaskTypes);
                        eventSourceFactory.newEventSource(requestTaskEvents(filterResult.buildId), buildProcessor);
                        return buildProcessor.getResult();
                    } else {
                        return CompletableFuture.completedFuture(BuildStatistics.EMPTY);
                    }
                }))
                .map(statsResult -> {
                    try {
                        return statsResult.get();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .reduce(BuildStatistics::merge)
                .orElse(BuildStatistics.EMPTY);

        result.print();

        if (buildOutputFile != null) {
            System.out.printf("Storing build IDs in %s%n", buildOutputFile);
            buildOutputFile.getParentFile().mkdirs();
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(buildOutputFile.toPath(), StandardCharsets.UTF_8))) {
                result.getBuildIds()
                        .forEach(writer::println);
            }
        }
        // Cleanly shuts down the HTTP client, which speeds up process termination
        shutdown(httpClient);
        return 0;
    }

    private static Stream<String> loadBuildsFromFile(File file) {
        System.out.println("Fetching build IDs from " + file);
        try {
            return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8).stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @NotNull
    private Stream<String> queryBuildsFromPast(Duration duration, EventSource.Factory eventSourceFactory) {
        Instant since = now().minus(duration);
        System.out.printf("Querying builds since %s%n", DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withZone(ZoneId.systemDefault())
                .format(since));
        StreamableQueue<String> buildQueue = new StreamableQueue<>("FINISHED");
        FilterBuildsByBuildTool buildToolFilter = new FilterBuildsByBuildTool(eventSourceFactory, buildQueue);
        eventSourceFactory.newEventSource(requestBuilds(since), buildToolFilter);
        return buildQueue.stream();
    }

    @NotNull
    private Request requestBuilds(Instant since) {
        return new Request.Builder()
                .url(serverUrl.resolve("/build-export/v2/builds/since/" + since.toEpochMilli()))
                .build();
    }

    @NotNull
    private Request requestBuildInfo(String buildId) {
        return new Request.Builder()
                .url(serverUrl.resolve("/build-export/v2/build/" + buildId + "/events?eventTypes=ProjectStructure,UserTag,BuildModes"))
                .build();
    }

    @NotNull
    private Request requestTaskEvents(String buildId) {
        return new Request.Builder()
                .url(serverUrl.resolve("/build-export/v2/build/" + buildId + "/events?eventTypes=TaskStarted,TaskFinished"))
                .build();
    }

    private static class FilterBuildsByBuildTool extends PrintFailuresEventSourceListener {
        private final EventSource.Factory eventSourceFactory;
        private final StreamableQueue<String> buildQueue;

        private FilterBuildsByBuildTool(EventSource.Factory eventSourceFactory, StreamableQueue<String> buildQueue) {
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
                buildQueue.close();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class ProcessBuildInfo extends PrintFailuresEventSourceListener {
        public static class Result {
            public final String buildId;
            public final boolean matches;
            public final int maxWorkers;

            public Result(String buildId, boolean matches, int maxWorkers) {
                this.buildId = buildId;
                this.matches = matches;
                this.maxWorkers = maxWorkers;
            }
        }

        private final String buildId;
        private final List<String> projectNames;
        private final CompletableFuture<Result> result = new CompletableFuture<>();
        private final List<String> rootProjects = new ArrayList<>();
        private final List<String> tags = new ArrayList<>();
        private int maxWorkers;

        private ProcessBuildInfo(String buildId, List<String> projectNames) {
            this.buildId = buildId;
            this.projectNames = projectNames;
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
                    case "BuildModes":
                        maxWorkers = eventJson.get("data").get("maxWorkers").asInt();
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
            if (!projectNames.stream().anyMatch(rootProjects::contains)) {
                matches = false;
            } else if (!tags.contains("LOCAL")) {
                matches = false;
            } else {
                System.out.println("Found build " + buildId);
                matches = true;
            }
            result.complete(new Result(buildId, matches, maxWorkers));
        }
    }

    private static class ProcessTaskEvents extends PrintFailuresEventSourceListener {
        private final String buildId;
        private final int maxWorkers;
        private final List<String> excludedTaskTypes;
        private final CompletableFuture<BuildStatistics> result = new CompletableFuture<>();
        private final Map<Long, TaskInfo> tasks = new HashMap<>();

        private static class TaskInfo {
            public final String type;
            public final String path;
            public final long startTime;
            public long finishTime;
            public String outcome;

            public TaskInfo(String type, String path, long startTime) {
                this.type = type;
                this.path = path;
                this.startTime = startTime;
            }

            // We did encounter a task that did not have an outcome somehow in GE scans
            // let's not have that fail the process
            public boolean isComplete() {
                return finishTime >= startTime && outcome != null;
            }
        }

        private ProcessTaskEvents(String buildId, int maxWorkers, List<String> excludedTaskTypes) {
            this.buildId = buildId;
            this.maxWorkers = maxWorkers;
            this.excludedTaskTypes = excludedTaskTypes;
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
                                eventJson.get("data").get("path").asText(),
                                timestamp
                        ));
                        break;
                    case "TaskFinished":
                        TaskInfo task = tasks.get(eventId);
                        task.finishTime = timestamp;
                        task.outcome = eventJson.get("data").get("outcome").asText();
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
            SortedMap<String, Long> taskTypeTimes = new TreeMap<>();
            SortedMap<String, Long> taskPathTimes = new TreeMap<>();
            tasks.values().stream()
                    .filter(TaskInfo::isComplete)
                    .filter(task -> excludedTaskTypes.stream().noneMatch(prefix -> task.type.startsWith(prefix)))
                    .filter(task -> task.outcome.equals("success") || task.outcome.equals("failed"))
                    .forEach(task -> {
                        taskCount.incrementAndGet();
                        add(taskTypeTimes, task.type, task.finishTime - task.startTime);
                        add(taskPathTimes, task.path, task.finishTime - task.startTime);
                        add(startStopEvents, task.startTime, 1);
                        add(startStopEvents, task.finishTime, -1);
                    });

            int concurrencyLevel = 0;
            long lastTimeStamp = 0;
            SortedMap<Integer, Long> histogram = new TreeMap<>();
            for (Map.Entry<Long, Integer> entry : startStopEvents.entrySet()) {
                long timestamp = entry.getKey();
                int delta = entry.getValue();
                if (concurrencyLevel != 0) {
                    long duration = timestamp - lastTimeStamp;
                    add(histogram, concurrencyLevel, duration);
                }
                concurrencyLevel += delta;
                lastTimeStamp = timestamp;
            }
            result.complete(new DefaultBuildStatistics(
                    ImmutableList.of(buildId),
                    taskCount.get(),
                    copyOfSorted(taskTypeTimes),
                    copyOfSorted(taskPathTimes),
                    copyOfSorted(histogram),
                    ImmutableSortedMap.of(maxWorkers, 1))
            );
        }
    }

    private static <K> void add(Map<K, Integer> map, K key, int delta) {
        map.compute(key, (k, value) -> (value == null ? 0 : value) + delta);
    }

    private static <K> void add(Map<K, Long> map, K key, long delta) {
        map.compute(key, (k, value) -> (value == null ? 0 : value) + delta);
    }

    private static <K extends Comparable<K>, V> ImmutableSortedMap<K, V> mergeMaps(Map<K, V> a, Map<K, V> b, V zero, BinaryOperator<V> add) {
        ImmutableSortedMap.Builder<K, V> merged = ImmutableSortedMap.naturalOrder();
        for (K key : Sets.union(a.keySet(), b.keySet())) {
            merged.put(key, add.apply(a.getOrDefault(key, zero), b.getOrDefault(key, zero)));
        }
        return merged.build();
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

    private interface BuildStatistics {
        public static final BuildStatistics EMPTY = new BuildStatistics() {
            @Override
            public List<String> getBuildIds() {
                return ImmutableList.of();
            }

            @Override
            public void print() {
                System.out.println("No matching builds found");
            }

            @Override
            public BuildStatistics merge(BuildStatistics other) {
                return other;
            }
        };

        List<String> getBuildIds();

        void print();

        BuildStatistics merge(BuildStatistics other);
    }

    private static class DefaultBuildStatistics implements BuildStatistics {
        private final ImmutableList<String> buildIds;
        private final int taskCount;
        private final ImmutableSortedMap<String, Long> taskTypeTimes;
        private final ImmutableSortedMap<String, Long> taskPathTimes;
        private final ImmutableSortedMap<Integer, Long> workerTimes;
        private final ImmutableSortedMap<Integer, Integer> maxWorkers;

        public DefaultBuildStatistics(
                ImmutableList<String> buildIds,
                int taskCount,
                ImmutableSortedMap<String, Long> taskTypeTimes,
                ImmutableSortedMap<String, Long> taskPathTimes,
                ImmutableSortedMap<Integer, Long> workerTimes,
                ImmutableSortedMap<Integer, Integer> maxWorkers
        ) {
            this.buildIds = buildIds;
            this.taskCount = taskCount;
            this.taskTypeTimes = taskTypeTimes;
            this.taskPathTimes = taskPathTimes;
            this.workerTimes = workerTimes;
            this.maxWorkers = maxWorkers;
        }

        @Override
        public ImmutableList<String> getBuildIds() {
            return buildIds;
        }

        @Override
        public void print() {
            System.out.println("Statistics for " + buildIds.size() + " builds with " + taskCount + " tasks");

            System.out.println();
            System.out.println("Concurrency levels:");
            int maxConcurrencyLevel = workerTimes.lastKey();
            for (int concurrencyLevel = maxConcurrencyLevel; concurrencyLevel >= 1; concurrencyLevel--) {
                System.out.printf("%d: %d ms%n", concurrencyLevel, workerTimes.getOrDefault(concurrencyLevel, 0L));
            }

            System.out.println();
            System.out.println("Task times by type:");
            taskTypeTimes.forEach((taskType, count) -> System.out.printf("%s: %d%n", taskType, count));

            System.out.println();
            System.out.println("Task times by path:");
            taskPathTimes.forEach((taskPath, count) -> System.out.printf("%s: %d%n", taskPath, count));

            System.out.println();
            System.out.println("Max workers:");
            int mostWorkers = maxWorkers.lastKey();
            for (int maxWorker = mostWorkers; maxWorker >= 1; maxWorker--) {
                System.out.printf("%d: %d builds%n", maxWorker, maxWorkers.getOrDefault(maxWorker, 0));
            }
        }

        @Override
        public BuildStatistics merge(BuildStatistics o) {
            if (o instanceof DefaultBuildStatistics) {
                DefaultBuildStatistics other = (DefaultBuildStatistics) o;
                ImmutableList<String> buildIds = ImmutableList.<String>builder().addAll(this.buildIds).addAll(other.buildIds).build();
                int taskCount = this.taskCount + other.taskCount;
                ImmutableSortedMap<String, Long> taskTypeTimes = mergeMaps(this.taskTypeTimes, other.taskTypeTimes, 0L, (aV, bV) -> aV + bV);
                ImmutableSortedMap<String, Long> taskPathTimes = mergeMaps(this.taskPathTimes, other.taskPathTimes, 0L, (aV, bV) -> aV + bV);
                ImmutableSortedMap<Integer, Long> workerTimes = mergeMaps(this.workerTimes, other.workerTimes, 0L, (aV, bV) -> aV + bV);
                ImmutableSortedMap<Integer, Integer> maxWorkers = mergeMaps(this.maxWorkers, other.maxWorkers, 0, (aV, bV) -> aV + bV);
                return new DefaultBuildStatistics(buildIds, taskCount, taskTypeTimes, taskPathTimes, workerTimes, maxWorkers);
            } else {
                return this;
            }
        }
    }

    private static class StreamableQueue<T> extends ForwardingBlockingQueue<T> {
        private final BlockingQueue<T> delegate;
        private final T poison;

        public StreamableQueue(T poison) {
            this.delegate = new LinkedBlockingQueue<>();
            this.poison = poison;
        }

        @Override
        protected BlockingQueue<T> delegate() {
            return delegate;
        }

        public void close() throws InterruptedException {
            put(poison);
        }

        @Override
        public Stream<T> stream() {
            return Stream.generate(() -> {
                        try {
                            T value = take();
                            if (value == poison) {
                                put(poison);
                            }
                            return value;
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .takeWhile(value -> value != poison);
        }
    }

    private static class HttpUrlConverter implements ITypeConverter<HttpUrl> {
        @Override
        public HttpUrl convert(String value) throws Exception {
            return HttpUrl.parse(value);
        }
    }

    private static class DurationConverter implements ITypeConverter<Duration> {
        @Override
        public Duration convert(String value) throws Exception {
            return Duration.parse(value);
        }
    }
}
