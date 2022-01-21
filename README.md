# Gradle Export API Parallelism Analyzer

![Build](https://github.com/lptr/analyze-builds/actions/workflows/build.yml/badge.svg)

Queries build scan data from a [Gradle Enterprise](https://gradle.com) server via the [Export API](https://docs.gradle.com/enterprise/export-api/), filters them and summarizes task execution and parallelism data.

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

