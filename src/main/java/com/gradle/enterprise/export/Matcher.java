package com.gradle.enterprise.export;

import picocli.CommandLine;

import java.util.regex.Pattern;

interface Matcher {
    boolean matches(String value);

    static class Converter implements CommandLine.ITypeConverter<Matcher> {
        @Override
        public Matcher convert(String value) throws Exception {
            if (value.startsWith("/") && value.endsWith("/")) {
                Pattern pattern = Pattern.compile(value.substring(1, value.length() - 1));
                return new Matcher() {
                    @Override
                    public boolean matches(String value1) {
                        return pattern.matcher(value1).matches();
                    }

                    @Override
                    public String toString() {
                        return "/" + pattern.pattern() + "/";
                    }
                };
            } else {
                return new Matcher() {
                    @Override
                    public boolean matches(String value1) {
                        return value.equals(value1);
                    }

                    @Override
                    public String toString() {
                        return "'" + value + "'";
                    }
                };
            }
        }
    }
}
