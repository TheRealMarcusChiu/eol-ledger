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
import java.util.regex.Pattern;

/**
 * Syncs release cycles from the endoflife.date v1 API (generated from github.com/endoflife-date/endoflife.date).
 * Whole catalogue = one GET {base}/products/full; or a configured product list = one GET per product.
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

    public SyncResult sync() {
        return sync(props.catalog().products().stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    synchronized SyncResult sync(List<String> products) {
        Instant started = clock.instant();
        List<String> failures = new ArrayList<>();
        int synced = 0;
        try {
            List<JsonNode> nodes = new ArrayList<>();
            if (products.isEmpty()) {
                fetch("/products/full").path("result").forEach(nodes::add);
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
            for (JsonNode n : nodes) {
                String name = n.path("name").asText("?");
                try {
                    save(parse(n));
                    synced++;
                } catch (RuntimeException e) {
                    failures.add(name + " (" + e.getMessage() + ")");
                }
            }
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
        byte[] body = Http.get(uri, null, props.catalog().timeout(), props.catalog().maxBytes());
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
