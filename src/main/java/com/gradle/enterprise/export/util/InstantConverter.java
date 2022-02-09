package com.gradle.enterprise.export.util;

import picocli.CommandLine;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class InstantConverter implements CommandLine.ITypeConverter<Instant> {
    @Override
    public Instant convert(String value) {
        List<String> parserProblems = new ArrayList<>();

        try {
            Duration duration = Duration.parse(value);
            return Instant.now().minus(duration);
        } catch (DateTimeParseException ex) {
            parserProblems.add(ex.getMessage());
        }

        try {
            return LocalDate.parse(value)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant();
        } catch (DateTimeParseException ex) {
            parserProblems.add("Cannot be parsed as LocalDate: " + ex.getMessage());
        }

        try {
            return LocalDateTime.parse(value)
                .atZone(ZoneId.systemDefault())
                .toInstant();
        } catch (DateTimeParseException ex) {
            parserProblems.add("Cannot be parsed as LocalDateTime: " + ex.getMessage());
        }

        throw new IllegalArgumentException(parserProblems.stream()
            .collect(Collectors.joining("\n")));
    }
}
