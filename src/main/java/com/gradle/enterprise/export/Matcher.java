package com.gradle.enterprise.export;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public abstract class Matcher {
    public enum Match {
        INCLUDE, EXCLUDE;
    }

    private final Match direction;

    public Matcher(Match direction) {
        this.direction = direction;
    }

    Match getDirection() {
        return direction;
    }

    public Optional<Match> matches(String value) {
        return match(value) ? Optional.of(direction) : Optional.empty();
    }

    public String describeAs(String title) {
        return String.format("%s %s %s",
            direction.name().toLowerCase(Locale.ROOT), title, describeValue());
    }

    protected abstract boolean match(String value);

    protected abstract String describeValue();

    public static class ExactMatcher extends Matcher {
        private final String value;

        public ExactMatcher(String value, Match direction) {
            super(direction);
            this.value = value;
        }

        @Override
        protected boolean match(String value) {
            return this.value.equals(value);
        }

        @Override
        protected String describeValue() {
            return "'" + value + "'";
        }
    }

    public static class RegexMatcher extends Matcher {
        private final Pattern pattern;

        public RegexMatcher(Pattern pattern, Match direction) {
            super(direction);
            this.pattern = pattern;
        }

        @Override
        protected boolean match(String value) {
            return pattern.matcher(value).matches();
        }

        @Override
        protected String describeValue() {
            return "/" + pattern.pattern() + "/";
        }
    }
}

