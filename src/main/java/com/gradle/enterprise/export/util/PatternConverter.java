package com.gradle.enterprise.export.util;

import picocli.CommandLine;

import java.util.regex.Pattern;

public class PatternConverter implements CommandLine.ITypeConverter<Pattern> {
    @Override
    public Pattern convert(String value) {
        return Pattern.compile(value);
    }
}
