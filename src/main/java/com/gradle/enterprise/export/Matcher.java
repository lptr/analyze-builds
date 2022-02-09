package com.gradle.enterprise.export;

import picocli.CommandLine;

import java.util.Locale;
import java.util.regex.Pattern;

abstract class Matcher {
    enum Type {
        INCLUDE, EXCLUDE
    }

    private final Type type;

    private Matcher(Type type) {
        this.type = type;
    }

    public boolean matches(String value) {
        boolean match = match(value);
        return type == Type.INCLUDE
                ? match
                : !match;
    }

    protected abstract boolean match(String value);

    @Override
    public String toString() {
        return type.name().toLowerCase(Locale.ROOT) + " " + describeValue();
    }

    protected abstract String describeValue();

    static class Converter implements CommandLine.ITypeConverter<Matcher> {
        @Override
        public Matcher convert(String value) {
            return value.startsWith("!")
                    ? convert(Type.EXCLUDE, value.substring(1))
                    : convert(Type.INCLUDE, value);
        }

        private Matcher convert(Type type, String value) {
            if (value.startsWith("/") && value.endsWith("/")) {
                return new RegexMatcher(type, Pattern.compile(value.substring(1, value.length() - 1)));
            } else {
                return new ExactMatcher(type, value);
            }
        }

        private static class ExactMatcher extends Matcher {
            private final String value;

            public ExactMatcher(Type type, String value) {
                super(type);
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

        private static class RegexMatcher extends Matcher {
            private final Pattern pattern;

            public RegexMatcher(Type type, Pattern pattern) {
                super(type);
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
}
