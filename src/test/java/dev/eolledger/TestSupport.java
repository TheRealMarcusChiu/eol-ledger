package dev.eolledger;

import com.sun.net.httpserver.HttpServer;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

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
            // headers and a few bytes, then the body stalls
            s.createContext("/stall", ex -> {
                ex.sendResponseHeaders(200, 1000);
                ex.getResponseBody().write(new byte[10]);
                ex.getResponseBody().flush();
                try {
                    Thread.sleep(15_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ex.close();
            });
            // a chunked body far larger than any test cap
            s.createContext("/huge", ex -> {
                ex.sendResponseHeaders(200, 0);
                for (int i = 0; i < 64; i++) ex.getResponseBody().write(new byte[4096]);
                ex.close();
            });
            s.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "test-http");
                t.setDaemon(true);
                return t;
            }));
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String base() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    /** Set by MySqlStorageTest only after its own CREATE DATABASE succeeded (CREATE fails if the name already exists). */
    static volatile String ownedMySqlSchema;

    /** Fail closed: the per-test DELETEs may only hit local in-memory H2 or the MySQL schema this run created. */
    static void requireDisposable(String product, String url, String catalog) {
        boolean h2Mem = "H2".equals(product) && url != null && url.startsWith("jdbc:h2:mem:");
        boolean ownedMySql = "MySQL".equals(product) && ownedMySqlSchema != null && ownedMySqlSchema.equals(catalog);
        if (!h2Mem && !ownedMySql) {
            throw new IllegalStateException("Refusing to clear test data: " + product + " database " + catalog
                    + " is neither in-memory H2 nor the MySQL schema created by this test run");
        }
    }

    static void reset(JdbcClient db, DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection()) {
            requireDisposable(c.getMetaData().getDatabaseProductName(), c.getMetaData().getURL(), c.getCatalog());
        }
        REPLIES.clear();
        AUTH_HEADERS.clear();
        for (String t : List.of("snapshot_inventory", "dependency", "snapshot", "repo_branch", "import_run", "release_cycle", "product_identifier", "product", "sync_status")) {
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
