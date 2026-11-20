package dev.eolledger;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/** Bounded HTTPS GET: never follows redirects, enforces a timeout and a body size cap, and produces URL-free error messages. */
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
        try {
            HttpResponse<InputStream> res = CLIENT.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = res.body()) {
                int code = res.statusCode();
                if (code >= 300 && code < 400) throw new FetchException("HTTP " + code + " redirect was not followed");
                if (code != 200) throw new FetchException("HTTP " + code);
                byte[] body = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
                if (body.length > maxBytes) throw new FetchException("response is larger than " + maxBytes + " bytes");
                return body;
            }
        } catch (FetchException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw new FetchException("timed out after " + timeout.toSeconds() + "s");
        } catch (ConnectException e) {
            throw new FetchException("connection failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("interrupted");
        } catch (IOException e) {
            throw new FetchException("network error (" + e.getClass().getSimpleName() + ")");
        }
    }
}
