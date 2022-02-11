package com.gradle.enterprise.export;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Consumer;

import static com.gradle.enterprise.export.Matcher.Match.EXCLUDE;
import static com.gradle.enterprise.export.Matcher.Match.INCLUDE;

interface Filter {

    boolean matches(String element);

    default boolean matchesAny(Collection<String> elements) {
        return elements.stream().anyMatch(this::matches);
    }

    boolean filters();

    void describeWith(Consumer<String> printer);

    public static Filter create(String title, @Nullable Collection<Matcher> matchers) {
        if (matchers == null || matchers.isEmpty()) {
            return new Filter() {
                @Override
                public boolean matches(String element) {
                    return true;
                }

                @Override
                public boolean filters() {
                    return false;
                }

                @Override
                public void describeWith(Consumer<String> printer) {
                    printer.accept("not filtering by " + title);
                }
            };
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
            public void describeWith(Consumer<String> printer) {
                matchers.stream()
                    .map(matcher -> matcher.describeAs(title))
                    .forEach(printer::accept);
            }
        };
    }
}
