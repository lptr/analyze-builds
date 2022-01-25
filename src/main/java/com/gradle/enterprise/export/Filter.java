package com.gradle.enterprise.export;

import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;

class Filter {
    private final Collection<Matcher> matchers;

    public Filter(Collection<Matcher> matchers) {
        this.matchers = matchers;
    }

    public boolean matches(String element) {
        return matches(Collections.singleton(element));
    }

    public boolean matches(Collection<String> elements) {
        return matchers != null && !elements.stream()
                .noneMatch(element -> matchers.stream()
                        .anyMatch(matcher -> matcher.matches(element)));
    }

    public boolean filters() {
        return matchers == null || matchers.isEmpty();
    }

    private static boolean match(Collection<Matcher> patterns, String value) {
        return patterns.stream().anyMatch(pattern -> pattern.matches(value));
    }

    @Override
    public String toString() {
        if (matchers == null || matchers.isEmpty()) {
            return "not filtering";
        } else {
            return matchers.stream()
                    .map(Matcher::toString)
                    .collect(Collectors.joining(", "));
        }
    }
}
