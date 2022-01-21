package com.gradle.enterprise.export.util;

import picocli.CommandLine;

import java.time.Duration;

public class DurationConverter implements CommandLine.ITypeConverter<Duration> {
    @Override
    public Duration convert(String value) {
        return Duration.parse(value);
    }
}
