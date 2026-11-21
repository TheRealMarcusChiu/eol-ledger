package dev.eolledger;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Read model for the pages. Matching runs at read time against the current catalogue, so EoL data
 * shown for historical snapshots is always today's upstream data.
 * shortcut: loads the catalogue and latest inventories per request; fine for hundreds of branches, add caching if it grows to many thousands.
 */
@Service
public class Ledger {

    public record Branch(long id, String repoName, String repoUrl, String provider, String branch, String sbomUrl,
                         Long snapshotId, Instant observedAt, int depCount) {
        public String repoLink() {
            return safeLink(repoUrl);
        }

        public String sbomLink() {
            return safeLink(sbomUrl);
        }
    }

    public record Cycle(String product, String productLabel, String productLink, String name, String label, LocalDate releaseDate,
                        Boolean isEol, LocalDate eolFrom, String latest, LocalDate latestDate) {
        public String key() {
            return product + " " + name;
        }
    }

    /** EoL status: css is one of ended, soon, ok, unknown. */
    public record Eol(String css, String text) {
        static Eol of(Cycle c, LocalDate today) {
            if (c == null) return new Eol("unknown", "Unknown: no matched release cycle");
            if (c.eolFrom() != null) {
                long days = ChronoUnit.DAYS.between(today, c.eolFrom());
                if (days < 0) return new Eol("ended", "Ended " + ago(-days));
                if (days == 0) return new Eol("ended", "Ends today");
                return new Eol(days <= 180 ? "soon" : "ok", "Ends in " + span(days));
            }
            if (Boolean.TRUE.equals(c.isEol())) return new Eol("ended", "Ended (date not published)");
            if (Boolean.FALSE.equals(c.isEol())) return new Eol("unknown", "No EoL date announced");
            return new Eol("unknown", "Unknown");
        }

        private static String ago(long days) {
            return span(days) + " ago";
        }

        private static String span(long days) {
            if (days < 60) return days + (days == 1 ? " day" : " days");
            if (days < 730) return (days / 30) + " months";
            return (days / 365) + " years";
        }
    }

    public record Dep(long id, String name, String version, String purl, String ecosystem, Matcher.Match match, Cycle cycle, Eol eol) {
        /** The stated version, else the purl's version; null when neither says. */
        public String installedVersion() {
            return version != null ? version : Matcher.purlVersion(purl);
        }

        /** Installed version with its provenance, e.g. "1.2 (from purl)" or "1.2 (purl: 1.3)" on a conflict. */
        public String displayVersion() {
            String fromPurl = Matcher.purlVersion(purl);
            if (version == null) return fromPurl == null ? "not stated" : fromPurl + " (from purl)";
            return fromPurl == null || fromPurl.equals(version) ? version : version + " (purl: " + fromPurl + ")";
        }
    }

    /**
     * Provenance of a snapshot uploaded as dependency-inventory JSON: what the upload declared (never fetched) plus
     * each package's graph contexts. contexts: flattened package key -> "project: scope, direct" lines.
     */
    public record Inline(String appId, String declaredSbomUrl, String generatedAt, boolean demo, String localRepository,
                         List<String> projects, Map<String, String> contexts) {
        public String declaredSbomLink() {
            return safeLink(declaredSbomUrl);
        }

        public String context(Dep d) {
            return contexts.getOrDefault(key(d.purl(), d.name(), d.version()), "");
        }

        /** Same identity the JSON import flattens on: normalized purl (else name) plus version. */
        static String key(String purl, String name, String version) {
            String n = Matcher.normalizePurl(purl);
            return (n != null ? n : name.trim()) + "\n" + (version == null ? "" : version.trim());
        }
    }

    public record DashRow(Cycle cycle, int repos, int branches, Eol eol) {
    }

    public record Affected(Branch branch, Dep dep) {
    }

    public record Snap(long id, Instant observedAt, String sbomUrl, int depCount, Diff diff) {
        public String sbomLink() {
            return safeLink(sbomUrl);
        }
    }

    public record Diff(List<String> added, List<String> removed, List<String> changed) {
        public boolean empty() {
            return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
        }
    }

    private final JdbcClient db;
    private final EolProperties props;
    private final Clock clock;
    private final DbTime time;

    public Ledger(JdbcClient db, EolProperties props, Clock clock, DbTime time) {
        this.db = db;
        this.props = props;
        this.clock = clock;
        this.time = time;
    }

    public ZoneId zone() {
        return props.zoneId();
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(props.zoneId()));
    }

    // ---- catalogue -------------------------------------------------------------------------

    private record Catalog(Map<String, Map<String, Cycle>> cycles, Matcher matcher) {
        Cycle cycle(Matcher.Match m) {
            return m.cycle() == null ? null : cycles.getOrDefault(m.product(), Map.of()).get(m.cycle());
        }
    }

    private Catalog catalog() {
        Map<String, Map<String, Cycle>> cycles = new HashMap<>();
        db.sql("""
                SELECT c.product, p.label AS plabel, p.html_link, c.name, c.label, c.release_date, c.is_eol, c.eol_from, c.latest, c.latest_date
                FROM release_cycle c JOIN product p ON p.name = c.product""")
                .query(rs -> {
                    Cycle c = cycle(rs);
                    cycles.computeIfAbsent(c.product(), k -> new LinkedHashMap<>()).put(c.name(), c);
                });
        Map<String, Set<String>> byPurl = new HashMap<>();
        db.sql("SELECT purl, product FROM product_identifier")
                .query(rs -> {
                    byPurl.computeIfAbsent(rs.getString(1), k -> new TreeSet<>()).add(rs.getString(2));
                });
        Map<String, List<String>> names = new HashMap<>();
        cycles.forEach((p, m) -> names.put(p, List.copyOf(m.keySet())));
        return new Catalog(cycles, new Matcher(byPurl, names, props.mappingTable()));
    }

    private static Cycle cycle(ResultSet rs) throws SQLException {
        boolean eol = rs.getBoolean("is_eol");
        Boolean isEol = rs.wasNull() ? null : eol;
        return new Cycle(rs.getString("product"), rs.getString("plabel"), rs.getString("html_link"), rs.getString("name"), rs.getString("label"),
                rs.getObject("release_date", LocalDate.class), isEol, rs.getObject("eol_from", LocalDate.class),
                rs.getString("latest"), rs.getObject("latest_date", LocalDate.class));
    }

    public record Product(String name, String label, String htmlLink, Instant syncedAt) {
        public String link() {
            return safeLink(htmlLink);
        }
    }

    public Product product(String name) {
        return db.sql("SELECT name, label, html_link, synced_at FROM product WHERE name = ?").param(name)
                .query((rs, i) -> new Product(rs.getString(1), rs.getString(2), rs.getString(3), time.read(rs, "synced_at")))
                .optional().orElse(null);
    }

    /** Only http(s) URLs become links; anything else is shown as text. */
    public static String safeLink(String url) {
        if (url == null) return null;
        String u = url.trim().toLowerCase(java.util.Locale.ROOT);
        return (u.startsWith("https://") || u.startsWith("http://")) && !url.contains("@") ? url : null;
    }

    public boolean catalogEmpty() {
        return db.sql("SELECT count(*) FROM product").query(Integer.class).single() == 0;
    }

    // ---- branches & dependencies ----------------------------------------------------------

    private static final String BRANCH_SQL = """
            SELECT b.*, s.id AS snap_id, s.observed_at, (SELECT count(*) FROM dependency d WHERE d.snapshot_id = s.id) AS deps
            FROM repo_branch b LEFT JOIN snapshot s ON s.id = (SELECT s2.id FROM snapshot s2 WHERE s2.branch_id = b.id ORDER BY s2.observed_at DESC, s2.id DESC LIMIT 1)
            """;

    public List<Branch> branches() {
        return db.sql(BRANCH_SQL + " ORDER BY lower(b.repo_name), b.repo_url, b.branch").query((rs, i) -> branch(rs)).list();
    }

    public Branch branch(long id) {
        return db.sql(BRANCH_SQL + " WHERE b.id = ?").param(id).query((rs, i) -> branch(rs)).optional().orElse(null);
    }

    private Branch branch(ResultSet rs) throws SQLException {
        long snap = rs.getLong("snap_id");
        Long snapId = rs.wasNull() ? null : snap;
        return new Branch(rs.getLong("id"), rs.getString("repo_name"), rs.getString("repo_url"), rs.getString("provider"), rs.getString("branch"),
                rs.getString("sbom_url"), snapId, time.read(rs, "observed_at"), rs.getInt("deps"));
    }

    public List<Dep> deps(long snapshotId) {
        return deps(snapshotId, catalog());
    }

    private List<Dep> deps(long snapshotId, Catalog cat) {
        LocalDate today = today();
        return db.sql("SELECT id, name, version, purl FROM dependency WHERE snapshot_id = ? ORDER BY lower(name), version, id").param(snapshotId)
                .query((rs, i) -> {
                    String purl = rs.getString("purl");
                    Matcher.Match m = cat.matcher().match(rs.getString("name"), rs.getString("version"), purl);
                    Cycle c = cat.cycle(m);
                    return new Dep(rs.getLong("id"), rs.getString("name"), rs.getString("version"), purl, ecosystem(purl), m, c,
                            m.product() == null ? new Eol("unknown", "Not mapped to an upstream product") : Eol.of(c, today));
                }).list();
    }

    public Dep dep(long branchId, long depId) {
        Long snap = db.sql("SELECT d.snapshot_id FROM dependency d JOIN snapshot s ON s.id = d.snapshot_id WHERE d.id = ? AND s.branch_id = ?")
                .params(depId, branchId).query(Long.class).optional().orElse(null);
        if (snap == null) return null;
        return deps(snap).stream().filter(d -> d.id() == depId).findFirst().orElse(null);
    }

    /** The JSON-upload provenance of a snapshot, or null for SBOM-fetched snapshots. */
    public Inline inline(long snapshotId) {
        String payload = db.sql("SELECT payload FROM snapshot_inventory WHERE snapshot_id = ?").param(snapshotId).query(String.class).optional().orElse(null);
        if (payload == null) return null;
        JsonNode e;
        try {
            e = InventoryJson.JSON.readTree(payload);
        } catch (IOException ex) {
            throw new IllegalStateException("stored inventory payload of snapshot " + snapshotId + " is not JSON", ex);
        }
        List<String> projects = new ArrayList<>();
        for (JsonNode p : e.path("projects")) {
            String tools = String.join(" via ", new LinkedHashSet<>(List.of(p.path("ecosystem").asText(""), p.path("packageManager").asText(""))).stream()
                    .filter(s -> !s.isEmpty()).toList());
            String status = p.path("resolution").path("status").asText("");
            projects.add(p.path("path").asText() + (tools.isEmpty() ? "" : ": " + tools) + (status.isEmpty() ? "" : " (" + status + ")"));
        }
        Map<String, TreeSet<String>> contexts = new HashMap<>();
        for (JsonNode d : e.path("dependencies")) {
            List<String> parts = new ArrayList<>();
            for (String f : List.of("scope", "dependencyType")) if (d.hasNonNull(f)) parts.add(d.get(f).asText());
            String c = String.join(", ", parts);
            if (d.path("depth").asInt() > 1) c += " (depth " + d.get("depth").asInt() + ")";
            if (d.path("optional").asBoolean()) c += ", optional";
            if (d.hasNonNull("projectPath")) c = d.get("projectPath").asText() + (c.isEmpty() ? "" : ": " + c);
            if (!c.isEmpty()) contexts.computeIfAbsent(Inline.key(d.path("purl").textValue(), d.path("name").asText(), d.path("version").asText()), k -> new TreeSet<>()).add(c);
        }
        Map<String, String> joined = new HashMap<>();
        contexts.forEach((k, v) -> joined.put(k, String.join("; ", v)));
        String url = e.path("url").asText();
        return new Inline(e.path("appId").textValue(), e.path("sbom").path("url").textValue(), e.path("sbom").path("generatedAt").textValue(),
                e.path("demo").asBoolean() || java.net.URI.create(url).getHost().endsWith(".invalid"), e.path("localRepository").textValue(), projects, joined);
    }

    /** App ID declared by this snapshot's JSON upload; null for CSV/SBOM snapshots, no snapshot, or none stated. Never falls back to older snapshots. */
    public String appId(Long snapshotId) {
        Inline i = snapshotId == null ? null : inline(snapshotId);
        return i == null || i.appId() == null || i.appId().isBlank() ? null : i.appId();
    }

    static String ecosystem(String purl) {
        if (purl == null || !purl.startsWith("pkg:") || purl.indexOf('/') < 0) return null;
        return purl.substring(4, purl.indexOf('/')).toLowerCase();
    }

    // ---- dashboard ------------------------------------------------------------------------

    public List<DashRow> dashboard(LocalDate start, LocalDate end, boolean desc, boolean showUnused) {
        Catalog cat = catalog();
        Map<String, Set<String>> repos = new HashMap<>();
        Map<String, Set<Long>> branchIds = new HashMap<>();
        for (Branch b : branches()) {
            if (b.snapshotId() == null) continue;
            for (Dep d : deps(b.snapshotId(), cat)) {
                if (d.cycle() == null) continue;
                repos.computeIfAbsent(d.cycle().key(), k -> new HashSet<>()).add(b.repoUrl());
                branchIds.computeIfAbsent(d.cycle().key(), k -> new HashSet<>()).add(b.id());
            }
        }
        LocalDate today = today();
        List<DashRow> rows = new ArrayList<>();
        for (Map<String, Cycle> byName : cat.cycles().values()) {
            for (Cycle c : byName.values()) {
                int r = repos.getOrDefault(c.key(), Set.of()).size();
                if (r == 0 && !showUnused) continue;
                if (!inRange(c.eolFrom(), start, end)) continue;
                rows.add(new DashRow(c, r, branchIds.getOrDefault(c.key(), Set.of()).size(), Eol.of(c, today)));
            }
        }
        Comparator<LocalDate> dates = desc ? Comparator.reverseOrder() : Comparator.naturalOrder();
        rows.sort(Comparator.comparing((DashRow d) -> d.cycle().eolFrom(), Comparator.nullsLast(dates))
                .thenComparing(d -> d.cycle().product()).thenComparing(d -> d.cycle().name()));
        return rows;
    }

    /** Both bounds blank = all time (unknown dates included); any bound excludes cycles without a published date. */
    static boolean inRange(LocalDate eol, LocalDate start, LocalDate end) {
        if (start == null && end == null) return true;
        if (eol == null) return false;
        return (start == null || !eol.isBefore(start)) && (end == null || !eol.isAfter(end));
    }

    /** Dependencies on the latest snapshots that matched a product but no release cycle. */
    public int unmatchedCycleCount() {
        Catalog cat = catalog();
        int n = 0;
        for (Branch b : branches()) {
            if (b.snapshotId() == null) continue;
            for (Dep d : deps(b.snapshotId(), cat)) if (d.match().product() == null || d.cycle() == null) n++;
        }
        return n;
    }

    // ---- product & cycle ------------------------------------------------------------------

    public List<Cycle> productCycles(String product) {
        List<Cycle> list = new ArrayList<>(catalog().cycles().getOrDefault(product, Map.of()).values());
        list.sort(Comparator.comparing(Cycle::releaseDate, Comparator.nullsFirst(Comparator.<LocalDate>naturalOrder())).reversed()
                .thenComparing(Cycle::name, Comparator.reverseOrder()));
        return list;
    }

    public Map<String, Integer> repoCountsByCycle(String product) {
        Map<String, Integer> out = new TreeMap<>();
        Map<String, Set<String>> repos = new HashMap<>();
        for (Affected a : affected(product, null)) {
            repos.computeIfAbsent(a.dep().cycle().name(), k -> new HashSet<>()).add(a.branch().repoUrl());
        }
        repos.forEach((k, v) -> out.put(k, v.size()));
        return out;
    }

    /** Latest-snapshot dependencies matched to the product (and cycle, when given). */
    public List<Affected> affected(String product, String cycle) {
        Catalog cat = catalog();
        List<Affected> out = new ArrayList<>();
        for (Branch b : branches()) {
            if (b.snapshotId() == null) continue;
            for (Dep d : deps(b.snapshotId(), cat)) {
                if (d.cycle() != null && d.cycle().product().equals(product) && (cycle == null || d.cycle().name().equals(cycle))) {
                    out.add(new Affected(b, d));
                }
            }
        }
        return out;
    }

    public Eol eol(Cycle c) {
        return Eol.of(c, today());
    }

    // ---- history --------------------------------------------------------------------------

    public List<Snap> timeline(long branchId) {
        record Row(long id, Instant observedAt, String sbomUrl) {
        }
        List<Row> snaps = db.sql("SELECT id, observed_at, sbom_url FROM snapshot WHERE branch_id = ? ORDER BY observed_at, id")
                .param(branchId).query((rs, i) -> new Row(rs.getLong("id"), time.read(rs, "observed_at"), rs.getString("sbom_url"))).list();
        // identity -> sorted set of versions per snapshot, so diffs never depend on row order
        Map<Long, Map<String, TreeSet<String>>> inventories = new HashMap<>();
        Map<Long, Integer> rowCounts = new HashMap<>();
        db.sql("SELECT d.snapshot_id, d.name, d.version, d.purl FROM dependency d JOIN snapshot s ON s.id = d.snapshot_id WHERE s.branch_id = ? ORDER BY d.id")
                .param(branchId).query(rs -> {
                    String n = Matcher.normalizePurl(rs.getString("purl"));
                    String key = n != null ? n : rs.getString("name").toLowerCase();
                    String version = rs.getString("version") != null ? rs.getString("version") : Matcher.purlVersion(rs.getString("purl"));
                    inventories.computeIfAbsent(rs.getLong("snapshot_id"), k -> new TreeMap<>())
                            .computeIfAbsent(key, k -> new TreeSet<>()).add(version == null ? "(no version)" : version);
                    rowCounts.merge(rs.getLong("snapshot_id"), 1, Integer::sum);
                });
        List<Snap> out = new ArrayList<>();
        Map<String, String> prev = Map.of();
        for (Row s : snaps) {
            long id = s.id();
            Map<String, String> inv = new TreeMap<>();
            inventories.getOrDefault(id, Map.of()).forEach((k, versions) -> inv.put(k, String.join(", ", versions)));
            out.add(new Snap(id, s.observedAt(), s.sbomUrl(), rowCounts.getOrDefault(id, 0), diff(prev, inv)));
            prev = inv;
        }
        return out;
    }

    static Diff diff(Map<String, String> before, Map<String, String> after) {
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        after.forEach((k, v) -> {
            if (!before.containsKey(k)) added.add(k + " " + v);
            else if (!Objects.equals(before.get(k), v)) changed.add(k + " " + before.get(k) + " → " + v);
        });
        before.forEach((k, v) -> {
            if (!after.containsKey(k)) removed.add(k + " " + v);
        });
        return new Diff(added, removed, changed);
    }

    /** Latest snapshot observed before the end of {@code day} in the configured zone, or null if none yet. */
    public Snap asOf(long branchId, LocalDate day) {
        Instant endOfDay = day.plusDays(1).atStartOfDay(props.zoneId()).toInstant();
        Snap found = null;
        for (Snap s : timeline(branchId)) if (s.observedAt().isBefore(endOfDay)) found = s;
        return found;
    }
}
