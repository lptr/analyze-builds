package com.gradle.enterprise.export;

import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;

class PrintFailuresEventSourceListener extends EventSourceListener {
    @Override
    public void onFailure(@Nonnull EventSource eventSource, @Nullable Throwable t, @Nullable Response response) {
        if (t != null) {
            System.err.println("FAILED: " + t.getMessage());
            t.printStackTrace();
        }
        if (response != null) {
            System.err.println("Bad response: " + response);
            System.err.println("Response body: " + getResponseBody(response));
        }
        eventSource.cancel();
        this.onClosed(eventSource);
    }

    @Nullable
    private String getResponseBody(Response response) {
        try {
            ResponseBody body = response.body();
            if (body == null) {
                return null;
            }
            return body.string();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
