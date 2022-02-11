package com.gradle.enterprise.export.util;

import com.gradle.enterprise.export.Matcher;
import picocli.CommandLine;

import java.util.regex.Pattern;

public class MatcherConverter implements CommandLine.ITypeConverter<Matcher> {
    @Override
    public Matcher convert(String value) {
        return value.startsWith("!")
            ? createMatcher(value.substring(1), Matcher.Match.EXCLUDE)
            : createMatcher(value, Matcher.Match.INCLUDE);
    }

    private static Matcher createMatcher(String value, Matcher.Match direction) {
        if (value.startsWith("/") && value.endsWith("/")) {
            return new Matcher.RegexMatcher(Pattern.compile(value.substring(1, value.length() - 1)), direction);
        } else {
            return new Matcher.ExactMatcher(value, direction);
        }
    }
}
