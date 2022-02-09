package com.gradle.enterprise.export;

import com.google.common.collect.ImmutableList;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;

class Filter {
    private final ImmutableList<Matcher> matchers;

    public Filter(@Nullable Collection<Matcher> matchers) {
        this.matchers = matchers == null
            ? ImmutableList.of()
            : ImmutableList.copyOf(matchers);
    }

    public boolean matches(String element) {
        return matches(Collections.singleton(element));
    }

    public boolean matches(Collection<String> elements) {
        return matchers.isEmpty() || elements.stream()
            .anyMatch(element -> matchers.stream()
                .anyMatch(matcher -> matcher.matches(element)));
    }

    public boolean filters() {
        return !matchers.isEmpty();
    }

    private static boolean match(Collection<Matcher> patterns, String value) {
        return patterns.stream().anyMatch(pattern -> pattern.matches(value));
    }

    @Override
    public String toString() {
        if (filters()) {
            return matchers.stream()
                .map(Matcher::toString)
                .collect(Collectors.joining(", "));
        } else {
            return "not filtering";
        }
    }
}
