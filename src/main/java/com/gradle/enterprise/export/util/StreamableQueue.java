package com.gradle.enterprise.export.util;

import com.google.common.util.concurrent.ForwardingBlockingQueue;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;

public class StreamableQueue<T> extends ForwardingBlockingQueue<T> {
    private final BlockingQueue<T> delegate;
    private final T poison;

    public StreamableQueue(T poison) {
        this.delegate = new LinkedBlockingQueue<>();
        this.poison = poison;
    }

    @Override
    protected BlockingQueue<T> delegate() {
        return delegate;
    }

    public void close() throws InterruptedException {
        put(poison);
    }

    @Override
    public Stream<T> stream() {
        return Stream.generate(() -> {
                    try {
                        T value = take();
                        if (value == poison) {
                            put(poison);
                        }
                        return value;
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                })
                .takeWhile(value -> value != poison);
    }
}
