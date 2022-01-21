# Gradle Export API Parallelism Analyzer

![Build](https://github.com/lptr/analyze-builds/actions/workflows/build.yml/badge.svg)

Queries build scan data from a [Gradle Enterprise](https://gradle.com) server via the [Export API](https://docs.gradle.com/enterprise/export-api/), filters them and summarizes task execution and parallelism data.

Currently the following data is collected:

- distribution of build time spent running 1, 2, 3 etc. tasks concurrently,
- distribution of build time broken down by task type,
- distribution of build time broken down by task path,
- distribution of the `org.gradle.workers.max` setting for the builds encountered.

### Limitations

The tool only processes task executions. It does not include worker API work and artifact transform executions, as build scans contain no information about these work types. For vanilla Java and Kotlin builds this shouldn't be a problem. It will distort results when measuring Android builds that rely on both transforms and the worker API.

The resutls only include a breakdown of the _execution phase_ of the Gradle build. They don't include the overhead added by Gradle's configuration phase, nor any IDE overhead. When considering actual developer experience these overheads should be accounted for.

## Minimum Gradle Enterprise version

This tool uses version 2 of the Export API, available since Gradle Enterprise 2021.2.

## Authentication

1. Create a Gradle Enterprise access key for a user with the `Export API` role as described in the [Export API Access Control] documentation.
2. Set an environment variable locally: `EXPORT_API_ACCESS_KEY` to match the newly created Gradle Enterprise access key. You can also pass it via the `--api-key` parameter.

## Invoking the tool

You can download the latest release from [releases](https://github.com/lptr/analyze-builds/releases). Once extracted, use the shell script or batch file in the `bin` directory to invoke the tool:

```bash
$ bin/analyze-builds \
    --server https://ge.gradle.org \
    --api-key ... \
    --include-project gradle \
    --include-tag LOCAL \
    --exclude-task-type org.gradle.api.tasks.testing.Test
```

Use `--help` to check all the options available.

### Using Gradle to build the tool from source and invoke it

```bash
$ ./gradlew :run --args="..."
```

