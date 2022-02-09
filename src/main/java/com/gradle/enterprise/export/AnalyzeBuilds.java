package com.gradle.enterprise.export;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.MoreExecutors;
import com.gradle.enterprise.export.util.DurationConverter;
import com.gradle.enterprise.export.util.HttpUrlConverter;
import com.gradle.enterprise.export.util.ManifestVersionProvider;
import com.gradle.enterprise.export.util.StreamableQueue;
import okhttp3.ConnectionPool;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSources;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BinaryOperator;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableSortedMap.copyOfSorted;
import static java.time.Instant.now;

@Command(
        name = "analyze-builds",
        description = "Analyze GE data",
        mixinStandardHelpOptions = true,
        customSynopsis = "analyze --server <URL> [OPTIONS...]",
        footer = "\nBy default, <pattern> matches explicitly. " +
                "When surrounded by /.../ the <pattern> is interpreted as a regular expression. " +
                "When prefixed wtih !, a <pattern> is treated as excluding.",
        versionProvider = ManifestVersionProvider.class,
        usageHelpWidth = 128,
        usageHelpAutoWidth = true
)
public final class AnalyzeBuilds implements Callable<Integer> {
    private static final Logger LOGGER = (Logger) LoggerFactory.getLogger(AnalyzeBuilds.class);

    @Option(names = "--server", paramLabel = "<URL>", required = true, description = "GE server URL", converter = HttpUrlConverter.class)
    private HttpUrl serverUrl;

    @Option(names = "--allow-untrusted", description = "Allow untrusted HTTPS connections")
    private boolean allowUntrusted;

    @Option(names = "--api-key", paramLabel = "<key>", description = "Export API access key, can be set via EXPORT_API_ACCESS_KEY environment variable")
    private String exportApiAccessKey = System.getenv("EXPORT_API_ACCESS_KEY");

    @Option(names = "--max-concurrency", paramLabel = "<n>", description = "Maximum number of build scans streamed concurrently")
    private int maxBuildScansStreamedConcurrently = 30;

    @Option(names = "--builds", split = ",", paramLabel = "<ID>", description = "Comma-separated list of build IDs to process")
    private List<String> builds;

    @Option(names = "--load-builds-from", paramLabel = "<file>", description = "File to load build IDs from (one ID per line); ignored when --builds is specified")
    private File buildInputFile;

    @Option(names = "--save-builds-to", paramLabel = "<file>", description = "File to save build IDs to")
    private File buildOutputFile;

    @Option(names = "--query-since", paramLabel = "<duration>", description = "Query builds in the given timeframe; defaults to two hours, see Duration.parse() for more info; ignored when --builds or --load-builds-from is specified", converter = DurationConverter.class)
    private Duration since = Duration.ofHours(2);

    @Option(names = "--project", paramLabel = "<pattern>", description = "Include/exclude builds with a matching root project", converter = Matcher.Converter.class)
    private List<Matcher> filterProjects;

    @Option(names = "--tag", paramLabel = "<pattern>", description = "Include/exclude builds with a matching tag", converter = Matcher.Converter.class)
    private List<Matcher> filterTags;

    @Option(names = "--requested-task", paramLabel = "<pattern>", description = "Include/eclude builds with a matching requested task", converter = Matcher.Converter.class)
    private List<Matcher> filterRequestedTasks;

    @Option(names = "--task-type", paramLabel = "<pattern>", description = "Include/exclude task with matching type", converter = Matcher.Converter.class)
    private List<Matcher> filterTaskTypes;

    @Option(names = "--task-path", paramLabel = "<pattern>", description = "Include/exclude task with matchin path", converter = Matcher.Converter.class)
    private List<Matcher> filterTaskPaths;

    @Option(names = "--log-task-type", paramLabel = "<pattern>", description = "Log task with matching type", converter = Matcher.Converter.class)
    private List<Matcher> logTaskTypes;

    @Option(names = "--log-task-path", paramLabel = "<pattern>", description = "Log task with matching path", converter = Matcher.Converter.class)
    private List<Matcher> logTaskPaths;

    @Option(names = "--verbose", description = "Enable verbose output")
    private boolean verbose;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) {
        int exitCode = new CommandLine(new AnalyzeBuilds()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        LOGGER.setLevel(verbose ? Level.DEBUG : Level.INFO);
        if (Strings.isNullOrEmpty(exportApiAccessKey)) {
            throw new RuntimeException("Export API access key must be specified");
        }
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(Duration.ZERO)
                .readTimeout(Duration.ZERO)
                .retryOnConnectionFailure(true)
                .connectionPool(new ConnectionPool(maxBuildScansStreamedConcurrently, 30, TimeUnit.SECONDS))
                .authenticator(Authenticators.bearerToken(exportApiAccessKey))
                .protocols(ImmutableList.of(Protocol.HTTP_1_1));
        if (allowUntrusted) {
            SSLContext sslContext = SSLContext.getInstance("SSL");
            X509TrustManager trustManager = new AllTrustingTrustManager();
            sslContext.init(null, new X509TrustManager[]{trustManager}, new SecureRandom());
            builder.sslSocketFactory(sslContext.getSocketFactory(), trustManager);
            builder.hostnameVerifier((hostname, session) -> true);
        }
        OkHttpClient httpClient = builder.build();
        httpClient.dispatcher().setMaxRequests(maxBuildScansStreamedConcurrently);
        httpClient.dispatcher().setMaxRequestsPerHost(maxBuildScansStreamedConcurrently);

        try {
            processEvents(httpClient);
        } finally {
            // Cleanly shuts down the HTTP client, which speeds up process termination
            httpClient.dispatcher().cancelAll();
            MoreExecutors.shutdownAndAwaitTermination(httpClient.dispatcher().executorService(), Duration.ofSeconds(10));
        }
        return 0;
    }

    private void processEvents(OkHttpClient httpClient) throws Exception {
        LOGGER.info("Connecting to GE server at {}", serverUrl);
        EventSource.Factory eventSourceFactory = EventSources.createFactory(httpClient);
        Stream<String> buildIds = builds != null ? builds.stream()
                : buildInputFile != null ? loadBuildsFromFile(buildInputFile)
                : queryBuildsFromPast(since, eventSourceFactory);
        Filter projectFilter = new Filter(filterProjects);
        Filter tagFilter = new Filter(filterTags);
        Filter requestedTaskFilter = new Filter(filterRequestedTasks);
        Filter taskTypeFilter = new Filter(filterTaskTypes);
        Filter taskPathFilter = new Filter(filterTaskPaths);
        Filter logTasksByTypeFilter = new Filter(logTaskTypes);
        Filter logTasksByPathFilter = new Filter(logTaskPaths);

        LOGGER.info("Filtering builds:");
        LOGGER.info(" - by project: {}", projectFilter);
        LOGGER.info(" - by tag: {}", tagFilter);
        LOGGER.info(" - by requested task: {}", requestedTaskFilter);

        LOGGER.info("Filtering tasks:");
        LOGGER.info(" - by task type: {}", taskTypeFilter);
        LOGGER.info(" - by task path: {}", taskPathFilter);

        LOGGER.info("Logging tasks:");
        LOGGER.info(" - by task type: {}", logTasksByTypeFilter);
        LOGGER.info(" - by task path: {}", logTasksByPathFilter);

        BuildStatistics composedStats = buildIds
                .parallel()
                .map(buildId -> processEventSource(eventSourceFactory, buildId, requestBuildInfo(buildId), new ProcessBuildInfo(buildId, projectFilter, tagFilter, requestedTaskFilter)))
                .map(future -> future.thenCompose(result -> {
                    if (result.matches) {
                        return processEventSource(
                                eventSourceFactory,
                                result.buildId,
                                requestTaskEvents(result.buildId),
                                new ProcessTaskEvents(
                                        result.buildId,
                                        result.maxWorkers,
                                        taskTypeFilter,
                                        taskPathFilter,
                                        logTasksByTypeFilter,
                                        logTasksByPathFilter
                                )
                        );
                    } else {
                        return CompletableFuture.completedFuture(BuildStatistics.EMPTY);
                    }
                }))
                .map(future -> future.exceptionally(error -> {
                            LOGGER.error("Couldn't process build, skipping", error);
                            return BuildStatistics.EMPTY;
                        })
                )
                .map(future -> {
                    try {
                        return future.get();
                    } catch (InterruptedException | ExecutionException e) {
                        throw new RuntimeException(e);
                    }
                })
                .reduce(BuildStatistics::merge)
                .orElse(BuildStatistics.EMPTY);

        composedStats.print();

        if (buildOutputFile != null) {
            LOGGER.info("Storing build IDs in {}", buildOutputFile);
            buildOutputFile.getParentFile().mkdirs();
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(buildOutputFile.toPath(), StandardCharsets.UTF_8))) {
                composedStats.getBuildIds()
                        .forEach(writer::println);
            }
        }
    }

    private static <T> CompletableFuture<T> processEventSource(EventSource.Factory eventSourceFactory, String buildId, Request request, BuildEventProcessor<T> processor) {
        BuildEventProcessingListener<T> listener = new BuildEventProcessingListener<>(buildId, processor);
        eventSourceFactory.newEventSource(request, listener);
        return listener.getResult();
    }

    private static Stream<String> loadBuildsFromFile(File file) {
        try {
            List<String> buildIds = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            LOGGER.info("Loaded {} build IDs from {}", buildIds.size(), file);
            return buildIds.stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Nonnull
    private Stream<String> queryBuildsFromPast(Duration duration, EventSource.Factory eventSourceFactory) {
        Instant since = now().minus(duration);
        LOGGER.info("Querying builds since {}", DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withZone(ZoneId.systemDefault())
                .format(since));
        StreamableQueue<String> buildQueue = new StreamableQueue<>("FINISHED");
        FilterBuildsByBuildTool buildToolFilter = new FilterBuildsByBuildTool(buildQueue);
        eventSourceFactory.newEventSource(requestBuilds(since), buildToolFilter);
        return buildQueue.stream();
    }

    @Nonnull
    @SuppressWarnings("ConstantConditions")
    private Request requestBuilds(Instant since) {
        return new Request.Builder()
                .url(serverUrl.resolve("/build-export/v2/builds/since/" + since.toEpochMilli()))
                .build();
    }

    @Nonnull
    @SuppressWarnings("ConstantConditions")
    private Request requestBuildInfo(String buildId) {
        return new Request.Builder()
                .url(serverUrl.resolve("/build-export/v2/build/" + buildId + "/events?eventTypes=ProjectStructure,UserTag,BuildModes,BuildRequestedTasks"))
                .build();
    }

    @Nonnull
    @SuppressWarnings("ConstantConditions")
    private Request requestTaskEvents(String buildId) {
        return new Request.Builder()
                .url(serverUrl.resolve("/build-export/v2/build/" + buildId + "/events?eventTypes=TaskStarted,TaskFinished"))
                .build();
    }

    private static class FilterBuildsByBuildTool extends PrintFailuresEventSourceListener {
        private final StreamableQueue<String> buildQueue;
        private final AtomicInteger buildCount = new AtomicInteger(0);

        private FilterBuildsByBuildTool(StreamableQueue<String> buildQueue) {
            this.buildQueue = buildQueue;
        }

        @Override
        public void onOpen(@Nonnull EventSource eventSource, @Nonnull Response response) {
            LOGGER.debug("Streaming builds...");
        }

        @Override
        public void onEvent(@Nonnull EventSource eventSource, @Nullable String id, @Nullable String type, @Nonnull String data) {
            JsonNode json = parse(data);
            JsonNode buildToolJson = json.get("toolType");
            if (buildToolJson != null && buildToolJson.asText().equals("gradle")) {
                String buildId = json.get("buildId").asText();
                buildCount.incrementAndGet();
                try {
                    buildQueue.put(buildId);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        @Override
        public void onClosed(@Nonnull EventSource eventSource) {
            LOGGER.info("Finished querying builds, found {}", buildCount.get());
            try {
                buildQueue.close();
            } catch (InterruptedException e) {
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

    private static class ProcessBuildInfo implements BuildEventProcessor<ProcessBuildInfo.Result> {
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
        private final Filter projectFilter;
        private final Filter tagFilter;
        private final Filter requestedTaskFilter;

        private final List<String> rootProjects = new ArrayList<>();
        private final List<String> tags = new ArrayList<>();
        private List<String> requestedTasks;
        private int maxWorkers;

        private ProcessBuildInfo(
                String buildId,
                Filter projectFilter,
                Filter tagFilter,
                Filter requestedTaskFilter
        ) {
            this.buildId = buildId;
            this.projectFilter = projectFilter;
            this.tagFilter = tagFilter;
            this.requestedTaskFilter = requestedTaskFilter;
        }

        @Override
        public void process(@Nullable String id, @Nonnull JsonNode eventJson) {
            String eventType = eventJson.get("type").get("eventType").asText();
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
                case "BuildRequestedTasks":
                    this.requestedTasks = ImmutableList.copyOf(Iterators.transform(eventJson.get("data").get("requested").elements(), JsonNode::asText));
                    break;
                default:
                    throw new AssertionError("Unknown event type: " + eventType);
            }
        }

        @Override
        public Result complete() {
            boolean matches = true
                    && projectFilter.matches(rootProjects)
                    && tagFilter.matches(tags)
                    && requestedTaskFilter.matches(requestedTasks);
            return new Result(buildId, matches, maxWorkers);
        }
    }

    private static class ProcessTaskEvents implements BuildEventProcessor<BuildStatistics> {
        private final String buildId;
        private final int maxWorkers;
        private final Filter taskTypeFilter;
        private final Filter taskPathFilter;
        private final Filter logTaskTypes;
        private final Filter logTaskPaths;
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
        }

        private ProcessTaskEvents(
                String buildId,
                int maxWorkers,
                Filter taskTypeFilter,
                Filter taskPathFilter,
                Filter logTaskTypes,
                Filter logTaskPaths
        ) {
            this.buildId = buildId;
            this.maxWorkers = maxWorkers;
            this.taskTypeFilter = taskTypeFilter;
            this.taskPathFilter = taskPathFilter;
            this.logTaskTypes = logTaskTypes;
            this.logTaskPaths = logTaskPaths;
        }

        @Override
        public void process(@Nullable String id, @Nonnull JsonNode eventJson) {
            long timestamp = eventJson.get("timestamp").asLong();
            String eventType = eventJson.get("type").get("eventType").asText();
            long eventId = eventJson.get("data").get("id").asLong();
            switch (eventType) {
                case "TaskStarted":
                    String type = eventJson.get("data").get("className").asText();
                    String path = eventJson.get("data").get("path").asText();
                    tasks.put(eventId, new TaskInfo(
                            type,
                            path,
                            timestamp
                    ));
                    if (logTaskTypes.filters() || logTaskPaths.filters()) {
                        if (logTaskTypes.matches(type) && logTaskPaths.matches(path)) {
                            LOGGER.info("Task {} with type {} found in build {}", path, type, buildId);
                        }
                    }
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

        @Override
        public BuildStatistics complete() {
            LOGGER.debug("Finished processing build {}", buildId);
            SortedMap<Long, Integer> startStopEvents = new TreeMap<>();
            AtomicInteger taskCount = new AtomicInteger(0);
            SortedMap<String, Long> taskTypeTimes = new TreeMap<>();
            SortedMap<String, Long> taskPathTimes = new TreeMap<>();
            tasks.values().stream()
                    .filter(task -> taskTypeFilter.matches(task.type))
                    .filter(task -> taskPathFilter.matches(task.path))
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
            return new DefaultBuildStatistics(
                    ImmutableList.of(buildId),
                    taskCount.get(),
                    copyOfSorted(taskTypeTimes),
                    copyOfSorted(taskPathTimes),
                    copyOfSorted(histogram),
                    ImmutableSortedMap.of(maxWorkers, 1)
            );
        }
    }

    private static <K> void add(Map<K, Integer> map, K key, int delta) {
        map.compute(key, (k, value) -> (value == null ? 0 : value) + delta);
    }

    private static <K> void add(Map<K, Long> map, K key, long delta) {
        map.compute(key, (k, value) -> (value == null ? 0 : value) + delta);
    }

    private interface BuildStatistics {
        public static final BuildStatistics EMPTY = new BuildStatistics() {
            @Override
            public List<String> getBuildIds() {
                return ImmutableList.of();
            }

            @Override
            public void print() {
                LOGGER.warn("No matching builds found");
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
            LOGGER.info("Statistics for {} builds matching criteria with {} tasks", buildIds.size(), taskCount);

            LOGGER.info("");
            LOGGER.info("Wall-clock time spent running n tasks concurrently:");
            for (int concurrencyLevel = 1; concurrencyLevel <= workerTimes.lastKey(); concurrencyLevel++) {
                LOGGER.info("{}: {} ms", concurrencyLevel, workerTimes.getOrDefault(concurrencyLevel, 0L));
            }

            LOGGER.info("");
            LOGGER.info("Cumlative build time broken down by task type:");
            taskTypeTimes.forEach((taskType, count) -> LOGGER.info("{}: {} ms", taskType, count));

            LOGGER.info("");
            LOGGER.info("Cumlative build time broken down by task path:");
            taskPathTimes.forEach((taskPath, count) -> LOGGER.info("{}: {} ms", taskPath, count));

            LOGGER.info("");
            LOGGER.info("Max workers:");
            for (int maxWorker = 1; maxWorker <= maxWorkers.lastKey(); maxWorker++) {
                LOGGER.info("{}: {} builds", maxWorker, maxWorkers.getOrDefault(maxWorker, 0));
            }
        }

        @Override
        public BuildStatistics merge(BuildStatistics o) {
            if (o instanceof DefaultBuildStatistics) {
                DefaultBuildStatistics other = (DefaultBuildStatistics) o;
                ImmutableList<String> buildIds = ImmutableList.<String>builder().addAll(this.buildIds).addAll(other.buildIds).build();
                int taskCount = this.taskCount + other.taskCount;
                ImmutableSortedMap<String, Long> taskTypeTimes = mergeMaps(this.taskTypeTimes, other.taskTypeTimes, 0L, Long::sum);
                ImmutableSortedMap<String, Long> taskPathTimes = mergeMaps(this.taskPathTimes, other.taskPathTimes, 0L, Long::sum);
                ImmutableSortedMap<Integer, Long> workerTimes = mergeMaps(this.workerTimes, other.workerTimes, 0L, Long::sum);
                ImmutableSortedMap<Integer, Integer> maxWorkers = mergeMaps(this.maxWorkers, other.maxWorkers, 0, Integer::sum);
                return new DefaultBuildStatistics(buildIds, taskCount, taskTypeTimes, taskPathTimes, workerTimes, maxWorkers);
            } else {
                return this;
            }
        }

        private static <K extends Comparable<K>, V> ImmutableSortedMap<K, V> mergeMaps(Map<K, V> a, Map<K, V> b, V zero, BinaryOperator<V> add) {
            ImmutableSortedMap.Builder<K, V> merged = ImmutableSortedMap.naturalOrder();
            for (K key : Sets.union(a.keySet(), b.keySet())) {
                merged.put(key, add.apply(a.getOrDefault(key, zero), b.getOrDefault(key, zero)));
            }
            return merged.build();
        }
    }

    private static class AllTrustingTrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[]{};
        }
    }
}
