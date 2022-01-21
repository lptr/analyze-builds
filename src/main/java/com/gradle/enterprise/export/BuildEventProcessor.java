package com.gradle.enterprise.export;

import com.fasterxml.jackson.databind.JsonNode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

interface BuildEventProcessor<T> {
    default void initialize() {
    }

    void process(@Nullable String id, @Nonnull JsonNode data);

    T complete();
}
