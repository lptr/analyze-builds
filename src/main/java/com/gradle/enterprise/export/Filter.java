package com.gradle.enterprise.export;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.gradle.enterprise.export.Matcher.Match.EXCLUDE;
import static com.gradle.enterprise.export.Matcher.Match.INCLUDE;

interface Filter {
    Filter MATCH_ALL = new Filter() {
        @Override
        public boolean matches(String element) {
            return true;
        }

        @Override
        public boolean filters() {
            return false;
        }
    };

    boolean matches(String element);

    default boolean matchesAny(Collection<String> elements) {
        return elements.stream().anyMatch(this::matches);
    }

    boolean filters();

    public static Filter from(@Nullable Collection<Matcher> matchers) {
        if (matchers == null || matchers.isEmpty()) {
            return MATCH_ALL;
        }
        Matcher.Match defaultResponse = matchers.stream()
            .noneMatch(matcher -> matcher.getDirection() == INCLUDE)
            ? INCLUDE
            : EXCLUDE;
        return new Filter() {
            @Override
            public boolean matches(String element) {
                return INCLUDE == matchers.stream()
                    .map(matcher -> matcher.matches(element))
                    .flatMap(Optional::stream)
                    .reduce((a, b) -> a == INCLUDE ? b : EXCLUDE)
                    .orElse(defaultResponse);

            }

            @Override
            public boolean filters() {
                return true;
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
        };
    }
}
