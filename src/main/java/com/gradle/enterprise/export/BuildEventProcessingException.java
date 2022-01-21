package com.gradle.enterprise.export;

class BuildEventProcessingException extends Exception {
    public BuildEventProcessingException(String buildId, String stage, Throwable cause) {
        super(String.format("Failed to %s build %s", stage, buildId), cause);
    }
}
