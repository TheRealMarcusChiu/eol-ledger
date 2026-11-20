package dev.eolledger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bounded HTTPS GET: never follows redirects, caps the body size while streaming, and enforces one
 * overall deadline covering connect, headers and the whole body (a stalled body is cancelled).
 * Error messages never contain the URL or credentials.
 */
final class Http {

    static class FetchException extends IOException {
        FetchException(String message) {
            super(message);
        }
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private Http() {
    }

    static byte[] get(URI uri, String bearerToken, Duration timeout, long maxBytes) throws FetchException {
        HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json").GET();
        if (bearerToken != null && !bearerToken.isBlank()) req.header("Authorization", "Bearer " + bearerToken);
        CompletableFuture<HttpResponse<byte[]>> call = CLIENT.sendAsync(req.build(),
                info -> info.statusCode() == 200 ? new Capped(maxBytes) : HttpResponse.BodySubscribers.replacing(null));
        HttpResponse<byte[]> res;
        try {
            res = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true); // aborts the exchange and releases the connection
            throw new FetchException("timed out after " + timeout.toSeconds() + "s");
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new FetchException("interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof FetchException f) throw f;
            if (cause instanceof HttpTimeoutException) throw new FetchException("timed out after " + timeout.toSeconds() + "s");
            if (cause instanceof ConnectException) throw new FetchException("connection failed");
            throw new FetchException("network error (" + (cause == null ? "unknown" : cause.getClass().getSimpleName()) + ")");
        }
        int code = res.statusCode();
        if (code >= 300 && code < 400) throw new FetchException("HTTP " + code + " redirect was not followed");
        if (code != 200) throw new FetchException("HTTP " + code);
        return res.body();
    }

    /** Collects the body but cancels the stream as soon as it exceeds the cap. */
    private static final class Capped implements HttpResponse.BodySubscriber<byte[]> {
        private final long max;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        Capped(long max) {
            this.max = max;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) return;
            for (ByteBuffer b : items) {
                if (buf.size() + (long) b.remaining() > max) {
                    subscription.cancel();
                    result.completeExceptionally(new FetchException("response is larger than " + max + " bytes"));
                    return;
                }
                byte[] chunk = new byte[b.remaining()];
                b.get(chunk);
                buf.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable t) {
            result.completeExceptionally(t);
        }

        @Override
        public void onComplete() {
            result.complete(buf.toByteArray());
        }
    }
}
