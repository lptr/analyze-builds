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
        public Matcher convert(String rawValue) {
            Type type;
            String value;
            if (rawValue.startsWith("!")) {
                type = Type.EXCLUDE;
                value = rawValue.substring(1);
            } else {
                type = Type.INCLUDE;
                value = rawValue;
            }
            if (value.startsWith("/") && value.endsWith("/")) {
                Pattern pattern = Pattern.compile(value.substring(1, value.length() - 1));
                return new Matcher(type) {

                    @Override
                    protected boolean match(String value) {
                        return pattern.matcher(value).matches();
                    }

                    @Override
                    protected String describeValue() {
                        return "/" + pattern.pattern() + "/";
                    }
                };
            } else {
                return new Matcher(type) {
                    @Override
                    protected boolean match(String value) {
                        return value.equals(value);
                    }

                    @Override
                    protected String describeValue() {
                        return "'" + value + "'";
                    }
                };
            }
        }
    }
}
