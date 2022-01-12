# Gradle Export API Parallelism extractor

Looks for local builds and tries to determine how parallel they are runnig.
The output shows how much time we spent runing on 1, 2, 3 etc. threads in parallel.

## Minimum Gradle Enterprise version

This sample uses version 2 of the Export API, available since Gradle Enterprise 2021.2.
In order to use it with older Gradle Enterprise versions, please modify all occurrences of `/build-export/v2` to `/build-export/v1` in `ExportApiJavaExample.java`.

## Setup

To run this sample:

1. Replace the hostname value of the `GRADLE_ENTERPRISE_SERVER` constant in [`ExportApiJavaExample.java`][ExportApiJavaExample] with your Gradle Enterprise hostname.
3. Set the appropriate authentication environment variables (see below).
2. Run `./gradlew run` from the command line.

### Authentication

1. Create a Gradle Enterprise access key for a user with the `Export API` role as described in the [Export API Access Control] documentation.
2. Set an environment variable locally: `EXPORT_API_ACCESS_KEY` to match the newly created Gradle Enterprise access key.
