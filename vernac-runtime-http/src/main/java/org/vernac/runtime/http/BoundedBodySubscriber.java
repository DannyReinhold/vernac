// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Subscription;

/** Bound buffering; the caller separately bounds the complete response duration. */
@NullMarked
final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    private final int limit;
    private final int status;
    private long received;
    private @Nullable Subscription subscription;
    private boolean done;

    BoundedBodySubscriber(int limit, int status) { this.limit = limit; this.status = status; }
    @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
    @Override public void onSubscribe(Subscription subscription) {
        this.subscription = subscription;
        delegate.onSubscribe(subscription);
    }
    @Override public void onNext(List<ByteBuffer> buffers) {
        if (done) return;
        for (var buffer : buffers) {
            received += buffer.remaining();
            if (received > limit) {
                done = true;
                Objects.requireNonNull(subscription).cancel();
                delegate.onError(new LimitExceeded(status));
                return;
            }
        }
        delegate.onNext(buffers);
    }
    @Override public void onError(Throwable error) {
        if (!done) { done = true; delegate.onError(error); }
    }
    @Override public void onComplete() {
        if (!done) { done = true; delegate.onComplete(); }
    }
    static final class LimitExceeded extends IOException {
        final int status;
        LimitExceeded(int status) { super("HTTP response exceeded configured byte limit"); this.status = status; }
    }
}
