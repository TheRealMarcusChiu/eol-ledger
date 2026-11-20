package dev.eolledger;

import com.sun.net.httpserver.HttpServer;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Shared test helpers: a loopback HTTP server with swappable responses, a settable clock, DB reset. */
final class TestSupport {

    record Reply(int status, String body, Map<String, String> headers) {
        static Reply ok(String body) {
            return new Reply(200, body, Map.of());
        }
    }

    static final Map<String, Reply> REPLIES = new ConcurrentHashMap<>();
    static final List<String> AUTH_HEADERS = new CopyOnWriteArrayList<>();
    static final HttpServer SERVER = start();

    private static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                AUTH_HEADERS.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
                Reply r = REPLIES.getOrDefault(ex.getRequestURI().getPath(), new Reply(404, "not found", Map.of()));
                r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
                byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(r.status(), b.length == 0 ? -1 : b.length);
                if (b.length > 0) ex.getResponseBody().write(b);
                ex.close();
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String base() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    static void reset(JdbcClient db) {
        REPLIES.clear();
        AUTH_HEADERS.clear();
        for (String t : List.of("dependency", "snapshot", "repo_branch", "import_run", "release_cycle", "product_identifier", "product", "sync_status")) {
            db.sql("DELETE FROM " + t).update();
        }
    }

    /** Clock the tests can move; registered as the primary Clock bean in integration tests. */
    static final class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-20T12:00:00Z");
        final ZoneId zone = ZoneId.of("Europe/Amsterdam");

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
