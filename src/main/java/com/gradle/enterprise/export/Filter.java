package com.gradle.enterprise.export;

import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;

class Filter {
    private final String title;
    private final Collection<Matcher> includes;
    private final Collection<Matcher> excludes;

    public Filter(String title, Collection<Matcher> includes, Collection<Matcher> excludes) {
        this.title = title;
        this.includes = includes;
        this.excludes = excludes;
    }

    public boolean matches(String element) {
        return matches(Collections.singleton(element));
    }

    public boolean matches(Collection<String> elements) {
        if (includes != null && !elements.stream().anyMatch(element -> match(includes, element))) {
            return false;
        }
        if (excludes != null && elements.stream().anyMatch(element -> match(excludes, element))) {
            return false;
        }
        return true;
    }

    public boolean filters() {
        return includes != null || excludes != null;
    }

    private static boolean match(Collection<Matcher> patterns, String value) {
        return patterns.stream().anyMatch(pattern -> pattern.matches(value));
    }

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

    private static String format(Collection<Matcher> patterns) {
        return patterns.stream()
                .map(Matcher::toString)
                .collect(Collectors.joining(", "));
    }
}
