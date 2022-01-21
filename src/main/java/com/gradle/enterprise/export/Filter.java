package com.gradle.enterprise.export;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

interface Filter {
    default boolean matches(String element) {
        return matches(Collections.singleton(element));
    }

    boolean matches(Collection<String> elements);

    static Filter with(String title, Pattern includes, Pattern excludes) {
        return new AbstractFilter<Pattern>(title, includes, excludes) {
            @Override
            protected boolean match(Pattern pattern, String element) {
                return pattern.matcher(element).matches();
            }

            @Override
            protected String format(Pattern pattern) {
                return "matching regex /" + pattern.pattern() + "/";
            }
        };
    }

    static Filter with(String title, Collection<String> includes, Collection<String> excludes) {
        return new AbstractFilter<Collection<String>>(title, includes, excludes) {
            @Override
            protected boolean match(Collection<String> pattern, String element) {
                return pattern.contains(element);
            }

            @Override
            protected String format(Collection<String> pattern) {
                return pattern.stream()
                        .map(element -> "'" + element + "'")
                        .collect(Collectors.joining(", "));
            }
        };
    }

    abstract class AbstractFilter<T> implements Filter {
        private final String title;
        private final T includes;
        private final T excludes;

        private AbstractFilter(String title, T includes, T excludes) {
            this.title = title;
            this.includes = includes;
            this.excludes = excludes;
        }

        @Override
        public boolean matches(Collection<String> elements) {
            if (includes != null && !elements.stream().anyMatch(element -> match(includes, element))) {
                return false;
            }
            if (excludes != null && elements.stream().anyMatch(element -> match(excludes, element))) {
                return false;
            }
            return true;
        }

        protected abstract boolean match(T pattern, String element);

        protected abstract String format(T pattern);

        @Override
        public String toString() {
            if (includes == null && excludes == null) {
                return "not filtering by " + title;
            } else {
                StringBuilder sb = new StringBuilder();
                if (includes != null) {
                    sb.append("include ");
                    sb.append(title);
                    sb.append(" ");
                    sb.append(format(includes));
                }
                if (excludes != null) {
                    if (includes != null) {
                        sb.append("; ");
                    }
                    sb.append("exclude ");
                    sb.append(title);
                    sb.append(" ");
                    sb.append(format(excludes));
                }
                return sb.toString();
            }
        }
    }
}
