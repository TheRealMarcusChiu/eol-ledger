package dev.eolledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Syncs release cycles from the endoflife.date v1 API (generated from github.com/endoflife-date/endoflife.date).
 * The public sync() always fetches the whole catalogue with one GET {base}/products/full; the package-private
 * sync(List) helper can instead fetch an explicit product list with one GET per product.
 * Each product's cycles and identifiers are replaced in its own transaction, so a failed fetch or
 * a malformed product never wipes previously synced data.
 */
@Service
public class CatalogService {

    public record SyncResult(boolean ok, int productsSynced, String message) {
    }

    public record Status(Instant attemptedAt, Instant succeededAt, boolean ok, String message) {
    }

    record Cycle(String name, String label, LocalDate releaseDate, Boolean isEol, LocalDate eolFrom, String latest, LocalDate latestDate) {
    }

    record ProductData(String name, String label, String html, Set<String> purls, List<Cycle> cycles) {
    }

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);
    private static final Pattern PRODUCT_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final EolProperties props;
    private final Clock clock;
    private final DbTime time;

    public CatalogService(JdbcClient db, TransactionTemplate tx, EolProperties props, Clock clock, DbTime time) {
        this.db = db;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
        this.time = time;
    }

    /** Live state of the current or last sync, counted at real work boundaries; read by the Imports page poll. */
    public record Progress(boolean running, String phase, int productsDone, int productsTotal, int cyclesDone, int cyclesTotal,
                           String current, Instant startedAt, Instant finishedAt, Boolean ok, String message) {
    }

    // One catalogue writer at a time (sync or clear): a second one is refused, never queued, so no request waits on it.
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile Progress progress = new Progress(false, "idle", 0, 0, 0, 0, null, null, null, null, null);

    boolean beginWork() {
        return busy.compareAndSet(false, true);
    }

    void endWork() {
        busy.set(false);
    }

    public boolean active() {
        return busy.get();
    }

    public Progress progress() {
        return progress;
    }

    public SyncResult sync() {
        return sync(List.of());
    }

    SyncResult sync(List<String> products) {
        if (!beginWork()) return new SyncResult(false, 0, "A catalogue sync is already running; this one was not started.");
        try {
            return runAndFinish(products, clock.instant());
        } finally {
            endWork();
        }
    }

    /** Starts the whole-catalogue sync on a background thread; false when a sync or clear is already active. */
    public boolean startAsync() {
        if (!beginWork()) return false;
        Instant started = clock.instant();
        progress = new Progress(true, "starting", 0, 0, 0, 0, null, started, null, null, null);
        try {
            Thread.ofPlatform().daemon().name("catalog-sync").start(() -> {
                try {
                    runAndFinish(List.of(), started);
                } finally {
                    endWork();
                }
            });
        } catch (RuntimeException | Error e) {
            finish(started, new SyncResult(false, 0, "Sync could not start: " + e.getMessage()));
            endWork();
            throw e;
        }
        return true;
    }

    /** Runs a sync and publishes its terminal state while the caller still owns the work gate, so a later job's progress is never overwritten. */
    private SyncResult runAndFinish(List<String> products, Instant started) {
        SyncResult r;
        try {
            r = run(products, started);
        } catch (RuntimeException e) {
            log.warn("Catalogue sync failed", e);
            r = new SyncResult(false, progress.productsDone(), "Sync failed: " + e.getMessage());
        }
        finish(started, r);
        return r;
    }

    private void finish(Instant started, SyncResult r) {
        Progress p = progress;
        progress = new Progress(false, r.ok() ? "succeeded" : "failed", p.productsDone(), p.productsTotal(), p.cyclesDone(), p.cyclesTotal(),
                null, started, clock.instant(), r.ok(), r.message());
    }

    private void step(String phase, int done, int total, int cyclesDone, int cyclesTotal, String current, Instant started) {
        progress = new Progress(true, phase, done, total, cyclesDone, cyclesTotal, current, started, null, null, null);
    }

    private SyncResult run(List<String> products, Instant started) {
        List<String> failures = new ArrayList<>();
        int synced = 0;
        int cyclesDone = 0;
        step("fetching", 0, 0, 0, 0, null, started);
        try {
            List<JsonNode> nodes = new ArrayList<>();
            if (products.isEmpty()) {
                JsonNode body = fetch("/products/full");
                JsonNode result = body.path("result"), total = body.path("total");
                // validate the whole envelope before any product is written, so a truncated catalogue keeps the last good data
                if (!result.isArray()) throw new Http.FetchException("catalogue response result is not an array");
                if (!total.isMissingNode() && !(total.isIntegralNumber() && total.canConvertToInt() && total.intValue() == result.size())) {
                    throw new Http.FetchException("catalogue response total does not match its " + result.size() + " product(s)");
                }
                result.forEach(nodes::add);
                if (nodes.isEmpty()) throw new Http.FetchException("catalogue response has no products");
            } else {
                for (String p : products) {
                    if (!PRODUCT_NAME.matcher(p).matches()) {
                        failures.add(p + " (invalid product name)");
                        continue;
                    }
                    try {
                        nodes.add(fetch("/products/" + p).path("result"));
                    } catch (IOException e) {
                        failures.add(p + " (" + e.getMessage() + ")");
                    }
                }
            }
            int cyclesTotal = 0;
            for (JsonNode n : nodes) cyclesTotal += n.path("releases").size();
            int done = 0;
            for (JsonNode n : nodes) {
                String name = n.path("name").asText("?");
                step("saving", done, nodes.size(), cyclesDone, cyclesTotal, name, started);
                try {
                    save(parse(n));
                    synced++;
                    cyclesDone += n.path("releases").size();
                } catch (RuntimeException e) {
                    failures.add(name + " (" + e.getMessage() + ")");
                }
                done++;
            }
            step("saving", done, nodes.size(), cyclesDone, cyclesTotal, null, started);
        } catch (IOException e) {
            failures.add(0, "catalogue fetch failed: " + e.getMessage());
        }
        boolean ok = failures.isEmpty();
        String message = "Synced " + synced + " product(s)" + (ok ? "" : "; failed: " + String.join(", ", failures));
        if (message.length() > 3900) message = message.substring(0, 3900) + "…";
        recordStatus(started, ok, synced > 0, message);
        if (!ok) log.warn("Catalogue sync: {}", message);
        return new SyncResult(ok, synced, message);
    }

    public Status status() {
        return db.sql("SELECT attempted_at, succeeded_at, ok, message FROM sync_status WHERE job = 'catalog'")
                .query((rs, i) -> new Status(time.read(rs, "attempted_at"), time.read(rs, "succeeded_at"), rs.getBoolean(3), rs.getString(4)))
                .optional().orElse(null);
    }

    private JsonNode fetch(String path) throws IOException {
        String base = props.catalog().baseUrl().replaceAll("/+$", "");
        URI uri = URI.create(base + path);
        String scheme = String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT);
        boolean loopback = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost());
        if (uri.getRawUserInfo() != null || !(scheme.equals("https") || (scheme.equals("http") && loopback))) {
            throw new Http.FetchException("eol.catalog.base-url must be HTTPS (HTTP only for loopback test servers) without credentials");
        }
        byte[] body = Http.get(uri, props.catalog().timeout(), props.catalog().maxBytes());
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new Http.FetchException("response is not valid JSON");
        }
    }

    static ProductData parse(JsonNode n) {
        String name = n.path("name").asText("");
        if (!PRODUCT_NAME.matcher(name).matches()) throw new IllegalArgumentException("missing or invalid product name");
        if (!n.path("releases").isArray()) throw new IllegalArgumentException("missing releases");
        Set<String> purls = new LinkedHashSet<>();
        for (JsonNode id : n.path("identifiers")) {
            if ("purl".equals(id.path("type").asText())) {
                String norm = Matcher.normalizePurl(id.path("id").asText());
                if (norm != null) purls.add(norm);
            }
        }
        List<Cycle> cycles = new ArrayList<>();
        for (JsonNode r : n.path("releases")) {
            String cycle = r.path("name").asText("");
            if (cycle.isBlank()) throw new IllegalArgumentException("release without name");
            JsonNode latest = r.path("latest");
            cycles.add(new Cycle(cycle, text(r.path("label")), date(r.path("releaseDate")),
                    r.path("isEol").isBoolean() ? r.path("isEol").asBoolean() : null,
                    date(r.path("eolFrom")), text(latest.path("name")), date(latest.path("date"))));
        }
        return new ProductData(name, n.path("label").asText(name), text(n.path("links").path("html")), purls, cycles);
    }

    private void save(ProductData p) {
        Object now = time.param(clock.instant());
        Map<String, Cycle> cycles = new LinkedHashMap<>(); // a repeated cycle name: last one wins
        p.cycles().forEach(c -> cycles.put(c.name(), c));
        tx.executeWithoutResult(s -> {
            db.sql("DELETE FROM release_cycle WHERE product = ?").param(p.name()).update();
            db.sql("DELETE FROM product_identifier WHERE product = ?").param(p.name()).update();
            if (db.sql("SELECT count(*) FROM product WHERE name = ?").param(p.name()).query(Integer.class).single() == 0) {
                db.sql("INSERT INTO product (name, label, html_link, synced_at) VALUES (?, ?, ?, ?)").params(p.name(), p.label(), p.html(), now).update();
            } else {
                db.sql("UPDATE product SET label = ?, html_link = ?, synced_at = ? WHERE name = ?").params(p.label(), p.html(), now, p.name()).update();
            }
            for (String purl : p.purls()) {
                db.sql("INSERT INTO product_identifier (product, purl) VALUES (?, ?)").params(p.name(), purl).update();
            }
            for (Cycle c : cycles.values()) {
                db.sql("INSERT INTO release_cycle (product, name, label, release_date, is_eol, eol_from, latest, latest_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                        .params(p.name(), c.name(), c.label(), c.releaseDate(), c.isEol(), c.eolFrom(), c.latest(), c.latestDate()).update();
            }
        });
    }

    private void recordStatus(Instant attempted, boolean ok, boolean anySuccess, String message) {
        if (db.sql("SELECT count(*) FROM sync_status WHERE job = 'catalog'").query(Integer.class).single() == 0) {
            db.sql("INSERT INTO sync_status (job) VALUES ('catalog')").update();
        }
        db.sql("UPDATE sync_status SET attempted_at = ?, ok = ?, message = ? WHERE job = 'catalog'")
                .params(time.param(attempted), ok, message).update();
        if (anySuccess) {
            db.sql("UPDATE sync_status SET succeeded_at = ? WHERE job = 'catalog'").param(time.param(attempted)).update();
        }
    }

    private static String text(JsonNode n) {
        return n.isTextual() && !n.asText().isBlank() ? n.asText() : null;
    }

    private static LocalDate date(JsonNode n) {
        if (n.isNull() || n.isMissingNode()) return null;
        try {
            return LocalDate.parse(n.asText());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid date");
        }
    }
}
