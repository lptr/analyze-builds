package com.gradle.enterprise.export;

import picocli.CommandLine;

import java.util.regex.Pattern;

interface Matcher {
    boolean matches(String value);

    class Converter implements CommandLine.ITypeConverter<Matcher> {
        @Override
        public Matcher convert(String value) {
            return value.startsWith("!")
                    ? new ExcludingMatcher(createMatcher(value.substring(1)))
                    : createMatcher(value);
        }

        private Matcher createMatcher(String value) {
            if (value.startsWith("/") && value.endsWith("/")) {
                return new RegexMatcher(Pattern.compile(value.substring(1, value.length() - 1)));
            } else {
                return new ExactMatcher(value);
            }
        }
    }

    class ExactMatcher implements Matcher {
        private final String value;

        public ExactMatcher(String value) {
            this.value = value;
        }

        @Override
        public boolean matches(String value) {
            return this.value.equals(value);
        }

        @Override
        public String toString() {
            return "'" + value + "'";
        }
    }

    class RegexMatcher implements Matcher {
        private final Pattern pattern;

        public RegexMatcher(Pattern pattern) {
            this.pattern = pattern;
        }

        @Override
        public boolean matches(String value) {
            return pattern.matcher(value).matches();
        }

        @Override
        public String toString() {
            return "/" + pattern.pattern() + "/";
        }
    }

    class ExcludingMatcher implements Matcher {
        private final Matcher delegate;

        public ExcludingMatcher(Matcher delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean matches(String value) {
            return !delegate.matches(value);
        }

        @Override
        public String toString() {
            return "exclude " + delegate.toString();
        }
    }
}

