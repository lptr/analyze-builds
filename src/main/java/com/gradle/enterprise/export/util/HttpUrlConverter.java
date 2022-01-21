package com.gradle.enterprise.export.util;

import okhttp3.HttpUrl;
import picocli.CommandLine;

public class HttpUrlConverter implements CommandLine.ITypeConverter<HttpUrl> {
    @Override
    public HttpUrl convert(String value) {
        return HttpUrl.parse(value);
    }
}
