package dev.eolledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * The dashboard table as a SQL read model (table dash_row): one row per catalogue release cycle, used or not, holding each
 * column's cell value exactly as app.js filters it, plus a natural-order rank per text column. Pages, counts, filters and
 * sorts run as parameterized SQL (WHERE / ORDER BY / LIMIT / OFFSET) with the browser's ?view.dependencies-table state.
 * The model is rebuilt from {@link Ledger#dashboard} whenever the catalogue, the inventories or the day change.
 */
@Service
@org.springframework.context.annotation.DependsOn("schemaUpgrade")
public class DashboardQuery {

    static final List<String> KEYS = List.of("purl", "ecosystem", "dependency", "version", "eol", "status", "repos", "branches",
            "context", "cycle", "evidence", "source", "sbom");
    /** Rows per page: any whole number 1..MAX_SIZE, else DEFAULT_SIZE (app.js validPageSize). */
    static final int DEFAULT_SIZE = 20, MAX_SIZE = 1000;
    static final Set<String> OPS = Set.of("contains", "not-contains", "exact", "not-exact", "starts-with", "ends-with", "fuzzy", "is-blank", "not-blank");
    /** Most values one values filter may list; far above any real column domain. */
    static final int MAX_VALUES = 20_000;

    public record Link(String href, String text) {
    }

    public record Url(String text, String link) {
    }

    record Links(List<Link> evidence, List<Url> sources, List<Url> sboms) {
    }

    public record Row(String product, String cycle, String href, String purl, String ecosystem, String dependency, String version,
                      String eol, String status, String statusCss, int repos, int branches, String context, String cycleLabel,
                      String matching, List<Link> evidence, List<Url> sources, List<Url> sboms) {
        public String versionText() {
            return version.isEmpty() ? "Not installed" : version;
        }

        public String eolText() {
            return eol.isEmpty() ? "Not published" : eol;
        }

        public String key() {
            return product + " " + cycle;
        }
    }

    public record Page(int total, int matching, int page, int pages, int size, List<Row> rows) {
    }

    public record Value(String value, String label) {
    }

    record Filter(String key, String mode, List<String> selected, List<String> excluded, String op, String text, String start, String end) {
    }

    record View(List<Filter> filters, String sortKey, String sortDir, int page, int size) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient db;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Ledger ledger;
    private final CatalogService catalog;
    final ReentrantLock rebuilding = new ReentrantLock();
    private volatile String builtFor;
    private volatile long builtAt;
    private volatile int unmatched;

    public DashboardQuery(JdbcClient db, JdbcTemplate jdbc, TransactionTemplate tx, Ledger ledger, CatalogService catalog) {
        this.db = db;
        this.jdbc = jdbc;
        this.tx = tx;
        this.ledger = ledger;
        this.catalog = catalog;
    }

    static String type(String key) {
        return switch (key) {
            case "version" -> "version";
            case "eol" -> "date";
            case "repos", "branches" -> "number";
            default -> "text";
        };
    }

    /** Installed packages of the latest snapshots that matched no release cycle (the dashboard hint). */
    public int unmatched() {
        fresh();
        return unmatched;
    }

    // ---- read model -------------------------------------------------------------------------

    private String fingerprint() {
        return ledger.today() + "|" + db.sql("SELECT count(*), max(synced_at) FROM product").query((rs, i) -> rs.getString(1) + "/" + rs.getString(2)).single()
                + "|" + db.sql("SELECT count(*) FROM release_cycle").query(String.class).single()
                + "|" + db.sql("SELECT count(*) FROM product_identifier").query(String.class).single()
                + "|" + db.sql("SELECT count(*) FROM repo_branch").query(String.class).single()
                + "|" + db.sql("SELECT count(*), max(id) FROM snapshot").query((rs, i) -> rs.getString(1) + "/" + rs.getString(2)).single();
    }

    /** Builds the read model at startup, so the first dashboard request does not hydrate the catalogue. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    void warm() {
        fresh();
    }

    /** Rebuilds the read model when its inputs changed; readers keep the previous model while another request rebuilds it. */
    void fresh() {
        String fp = fingerprint();
        if (fp.equals(builtFor)) return;
        // shortcut: while a sync runs, rebuild at most every 3 s so polling readers see progress without rebuilding per product
        if (builtFor != null && catalog.active() && System.nanoTime() - builtAt < 3_000_000_000L) return;
        if (!rebuilding.tryLock()) {
            // only a running sync may be answered from the previous model; otherwise wait for the rebuild, then recheck,
            // so a read after the sync (or after a clear racing a rebuild) never returns a stale model
            if (builtFor != null && catalog.active()) return;
            rebuilding.lock();
        }
        try {
            fp = fingerprint();
            if (!fp.equals(builtFor)) {
                rebuild();
                builtFor = fp;
                builtAt = System.nanoTime();
            }
        } finally {
            rebuilding.unlock();
        }
    }

    private static final List<String> RANKED = List.of("purl", "ecosystem", "dependency", "version", "status", "context", "cycle", "evidence", "source", "sbom");

    private void rebuild() {
        Ledger.Dashboard dash = ledger.dashboard(null, null, false, true);
        List<Ledger.DashRow> rows = dash.rows();
        int n = rows.size();
        Map<String, String[]> values = new HashMap<>();
        for (String k : RANKED) values.put(k, new String[n]);
        List<Links> links = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Ledger.DashRow r = rows.get(i);
            Ledger.Cycle c = r.cycle();
            List<Link> evidence = r.inventory().stream().map(e -> new Link("/repos/" + e.branch().id() + "/deps/" + e.dep().id(),
                    e.branch().nameText() + " / " + e.branch().branchText() + ": Details")).toList();
            List<Url> sources = r.sources().stream().map(s -> new Url(s.text(), s.link())).toList();
            List<Url> sboms = r.sboms().stream().map(s -> new Url(s.text(), s.link())).toList();
            links.add(new Links(evidence, sources, sboms));
            values.get("purl")[i] = cell(r.purls());
            values.get("ecosystem")[i] = cell(r.ecosystems());
            values.get("dependency")[i] = r.names();
            // the shipped page gave not-installed rows no data-value, so the browser filtered and sorted on their text
            values.get("version")[i] = r.inventory().isEmpty() ? "Not installed" : r.versions();
            values.get("status")[i] = r.eol().text();
            values.get("context")[i] = cell(r.contexts());
            values.get("cycle")[i] = cell(c.productLabel() + " " + (c.label() != null ? c.label() : c.name()));
            // the cells' visible text as the browser collapsed it: the matching text, then each link / URL, space separated
            List<String> ev = new ArrayList<>();
            ev.add(r.matching());
            evidence.forEach(l -> ev.add(l.text()));
            values.get("evidence")[i] = cell(String.join(" ", ev));
            values.get("source")[i] = cell(String.join(" ", sources.stream().map(Url::text).toList()));
            values.get("sbom")[i] = cell(String.join(" ", sboms.stream().map(Url::text).toList()));
        }
        Map<String, Integer[]> ranks = new HashMap<>();
        for (String k : RANKED) ranks.put(k, ranks(values.get(k)));
        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Ledger.DashRow r = rows.get(i);
            Ledger.Cycle c = r.cycle();
            String linksJson;
            try {
                linksJson = JSON.writeValueAsString(links.get(i));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            List<Object> row = new ArrayList<>(List.of(c.product(), c.name(), i));
            for (String k : List.of("purl", "ecosystem", "dependency", "version")) {
                row.add(values.get(k)[i]);
                row.add(ranks.get(k)[i]);
            }
            row.add(c.eolFrom() == null ? "" : c.eolFrom().toString());
            row.add(c.eolFrom());
            row.add(values.get("status")[i]);
            row.add(ranks.get("status")[i]);
            row.add(r.eol().css());
            row.add(r.repos());
            row.add(r.branches());
            for (String k : List.of("context", "cycle", "evidence", "source", "sbom")) {
                row.add(values.get(k)[i]);
                row.add(ranks.get(k)[i]);
            }
            row.add(r.matching());
            row.add(linksJson);
            batch.add(row.toArray());
        }
        tx.executeWithoutResult(s -> {
            db.sql("DELETE FROM dash_row").update();
            jdbc.batchUpdate("""
                    INSERT INTO dash_row (product, cycle, ord, c_purl, r_purl, c_ecosystem, r_ecosystem, c_dependency, r_dependency,
                      c_version, r_version, c_eol, eol_from, c_status, r_status, status_css, c_repos, c_branches, c_context, r_context,
                      c_cycle, r_cycle, c_evidence, r_evidence, c_source, r_source, c_sbom, r_sbom, matching, links)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", batch);
        });
        unmatched = dash.unmatched();
    }

    /** A cell's text as the browser reads it: whitespace runs collapsed, trimmed. */
    static String cell(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** Dense natural-order ranks of the non-blank values; blank ("") has no rank and sorts last. */
    static Integer[] ranks(String[] values) {
        Integer[] idx = new Integer[values.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> NATURAL.compare(values[a], values[b]));
        Integer[] out = new Integer[values.length];
        int rank = 0;
        String prev = null;
        for (Integer i : idx) {
            if (values[i].isEmpty()) continue;
            if (prev != null && NATURAL.compare(prev, values[i]) != 0) rank++;
            out[i] = rank;
            prev = values[i];
        }
        return out;
    }

    /** CLDR root order of ASCII punctuation and symbols (non-ignorable), as ICU / Intl.Collator uses them. */
    private static final String PUNCT = "_-,;:!?.'\"()[]{}@*/\\&#%`^+<=>|~$";
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    /** Primary weight of one non-digit character: whitespace < punctuation < other symbols < digits < letters. */
    private static int weight(char c) {
        if (Character.isWhitespace(c)) return 0;
        int p = PUNCT.indexOf(c);
        if (p >= 0) return 1 + p;
        if (Character.isDigit(c)) return 100_000;
        if (Character.isLetter(c)) return 200_000 + c;
        return 1_000 + c;
    }

    /**
     * The browser's Intl.Collator('en', {numeric: true, sensitivity: 'base'}) at primary strength: the whole string is
     * compared element by element (never chunk by chunk), digit runs as numbers, case and accents ignored.
     * shortcut: ASCII punctuation follows CLDR root; other scripts' symbols and letters order by code point.
     */
    static final Comparator<String> NATURAL = (a, b) -> {
        String x = fold(a), y = fold(b);
        int i = 0, j = 0;
        while (i < x.length() && j < y.length()) {
            char p = x.charAt(i), q = y.charAt(j);
            if (Character.isDigit(p) && Character.isDigit(q)) {
                int si = i, sj = j;
                while (i < x.length() && Character.isDigit(x.charAt(i))) i++;
                while (j < y.length() && Character.isDigit(y.charAt(j))) j++;
                String u = x.substring(si, i).replaceFirst("^0+(?=\\d)", ""), v = y.substring(sj, j).replaceFirst("^0+(?=\\d)", "");
                int c = u.length() != v.length() ? Integer.compare(u.length(), v.length()) : u.compareTo(v);
                if (c != 0) return c;
                continue;
            }
            int c = Integer.compare(weight(p), weight(q));
            if (c != 0) return c;
            i++;
            j++;
        }
        return Integer.compare(x.length() - i, y.length() - j);
    };

    private static String fold(String s) {
        return MARKS.matcher(java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT);
    }

    // ---- view state (app.js decodeView) -----------------------------------------------------------

    /** Untrusted ?view text to a view: anything missing, malformed, unknown or of another version falls back to its default. */
    static View parse(String text) {
        List<Filter> filters = new ArrayList<>();
        String sortKey = "eol", sortDir = "asc";
        int page = 1, size = DEFAULT_SIZE;
        JsonNode v = null;
        try {
            if (text != null) v = JSON.readTree(text);
        } catch (JsonProcessingException e) {
            // defaults
        }
        if (v == null || !v.isObject() || !v.path("v").isIntegralNumber() || v.path("v").asLong() != 1) return new View(filters, sortKey, sortDir, page, size);
        JsonNode fs = v.path("filters");
        for (String k : KEYS) {
            JsonNode s = fs.isObject() ? fs.get(k) : null;
            if (s == null || !s.isObject()) continue;
            String mode = "values";
            if (s.path("mode").isTextual() && List.of("values", "text", "range").contains(s.get("mode").asText())) mode = s.get("mode").asText();
            List<String> selected = null, excluded = null;
            if (s.has("selected") && (s.get("selected").isNull() || strings(s.get("selected")))) selected = s.get("selected").isNull() ? null : list(s.get("selected"));
            else if (s.has("excluded") && strings(s.get("excluded"))) excluded = list(s.get("excluded"));
            String op = s.path("op").isTextual() && OPS.contains(s.get("op").asText()) ? s.get("op").asText() : "contains";
            String txt = str(s, "text");
            String type = type(k);
            String[] range = checkRange(type, str(s, "startText"), str(s, "endText"));
            if (range == null) {
                // an invalid draft carries the bounds it kept applied; they must pass the same validators
                boolean carried = s.has("start") && s.has("end") && bound(s.get("start")) && bound(s.get("end"));
                range = carried ? checkRange(type, s.get("start").isNull() ? "" : s.get("start").asText(), s.get("end").isNull() ? "" : s.get("end").asText()) : null;
                if (range == null) range = new String[]{null, null};
            }
            if ((selected != null && selected.size() > MAX_VALUES) || (excluded != null && excluded.size() > MAX_VALUES)) {
                throw new IllegalArgumentException("Too many selected values in one filter.");
            }
            filters.add(new Filter(k, mode, selected, excluded, op, txt, range[0], range[1]));
        }
        JsonNode sort = v.get("sort");
        if (sort != null && sort.isNull()) {
            sortKey = null;
            sortDir = null;
        } else if (sort != null && sort.isObject() && sort.path("key").isTextual() && KEYS.contains(sort.get("key").asText())
                && sort.path("dir").isTextual() && List.of("asc", "desc").contains(sort.get("dir").asText())) {
            sortKey = sort.get("key").asText();
            sortDir = sort.get("dir").asText();
        }
        JsonNode p = v.get("page");
        if (p != null && p.isIntegralNumber() && p.canConvertToInt() && p.asInt() > 1) page = p.asInt();
        JsonNode z = v.get("size");
        // 7.0 is the whole number 7 (as JSON.parse reads it); 7.5 or out-of-range values are rejected, never truncated
        if (z != null && z.isNumber() && z.canConvertToExactIntegral() && z.canConvertToInt() && z.asInt() >= 1 && z.asInt() <= MAX_SIZE) size = z.asInt();
        return new View(filters, sortKey, sortDir, page, size);
    }

    private static boolean strings(JsonNode a) {
        if (!a.isArray()) return false;
        for (JsonNode x : a) if (!x.isTextual()) return false;
        return true;
    }

    private static List<String> list(JsonNode a) {
        List<String> out = new ArrayList<>();
        a.forEach(x -> out.add(x.asText()));
        return out;
    }

    private static String str(JsonNode s, String name) {
        return s.path(name).isTextual() ? s.get(name).asText() : "";
    }

    private static boolean bound(JsonNode x) {
        return x.isNull() || x.isTextual();
    }

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Pattern DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

    /** app.js parseBound: the trimmed bound, null when blank; an exception when invalid. */
    private static String bound(String type, String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) return null;
        if (type.equals("number") && !NUMBER.matcher(t).matches()) throw new IllegalArgumentException();
        if (type.equals("date")) {
            var m = DATE.matcher(t);
            if (!m.matches()) throw new IllegalArgumentException();
            try {
                LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            } catch (java.time.DateTimeException e) {
                throw new IllegalArgumentException();
            }
        }
        return t;
    }

    /** app.js checkRange: {start, end} (nulls when open), or null when either bound is invalid or the range is reversed. */
    static String[] checkRange(String type, String start, String end) {
        String s, e;
        try {
            s = bound(type, start);
            e = bound(type, end);
        } catch (IllegalArgumentException x) {
            return null;
        }
        if (s != null && e != null && compareTyped(type, s, e) > 0) return null;
        return new String[]{s, e};
    }

    static int compareTyped(String type, String a, String b) {
        if (type.equals("number")) return new BigDecimal(a).compareTo(new BigDecimal(b));
        if (type.equals("date")) return a.compareTo(b);
        return NATURAL.compare(a, b);
    }

    // ---- SQL --------------------------------------------------------------------------------------

    /** The SQL expression holding a column's filter value; keys are whitelisted, never user text. */
    private static String expr(String key) {
        return switch (key) {
            case "eol" -> "c_eol";
            case "repos", "branches" -> "CONCAT('', c_" + key + ")";
            default -> "c_" + key;
        };
    }

    private record Where(List<String> sql, List<Object> params, List<Filter> fuzzy) {
        String clause() {
            return sql.isEmpty() ? "" : " WHERE " + String.join(" AND ", sql);
        }
    }

    private Where where(View view, boolean usedOnly, boolean filtered) {
        List<String> sql = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        List<Filter> fuzzy = new ArrayList<>();
        if (usedOnly) sql.add("c_repos > 0");
        if (!filtered) return new Where(sql, params, fuzzy);
        for (Filter f : view.filters()) {
            String k = f.key(), x = expr(k);
            switch (f.mode()) {
                case "values" -> {
                    List<String> vals = f.selected() != null ? f.selected() : f.excluded();
                    if (vals == null) continue;
                    boolean in = f.selected() != null;
                    if (vals.isEmpty()) {
                        if (in) sql.add("1 = 0");
                        continue;
                    }
                    sql.add(x + (in ? " IN (" : " NOT IN (") + String.join(", ", java.util.Collections.nCopies(vals.size(), "?")) + ")");
                    params.addAll(vals);
                }
                case "text" -> {
                    String q = f.text().strip().toLowerCase(Locale.ROOT);
                    switch (f.op()) {
                        case "is-blank" -> sql.add("TRIM(" + x + ") = ''");
                        case "not-blank" -> sql.add("TRIM(" + x + ") <> ''");
                        default -> {
                            if (q.isEmpty()) continue;
                            String like = q.replace("!", "!!").replace("%", "!%").replace("_", "!_");
                            switch (f.op()) {
                                case "fuzzy" -> fuzzy.add(f);
                                case "exact" -> { sql.add("LOWER(" + x + ") = ?"); params.add(q); }
                                case "not-exact" -> { sql.add("LOWER(" + x + ") <> ?"); params.add(q); }
                                case "starts-with" -> { sql.add("LOWER(" + x + ") LIKE ? ESCAPE '!'"); params.add(like + "%"); }
                                case "ends-with" -> { sql.add("LOWER(" + x + ") LIKE ? ESCAPE '!'"); params.add("%" + like); }
                                case "not-contains" -> { sql.add("LOWER(" + x + ") NOT LIKE ? ESCAPE '!'"); params.add("%" + like + "%"); }
                                default -> { sql.add("LOWER(" + x + ") LIKE ? ESCAPE '!'"); params.add("%" + like + "%"); }
                            }
                        }
                    }
                }
                default -> {
                    if (f.start() == null && f.end() == null) continue;
                    switch (type(k)) {
                        case "number" -> {
                            if (f.start() != null) { sql.add("c_" + k + " >= ?"); params.add(new BigDecimal(f.start())); }
                            if (f.end() != null) { sql.add("c_" + k + " <= ?"); params.add(new BigDecimal(f.end())); }
                        }
                        case "date" -> {
                            sql.add("eol_from IS NOT NULL");
                            if (f.start() != null) { sql.add("eol_from >= ?"); params.add(LocalDate.parse(f.start())); }
                            if (f.end() != null) { sql.add("eol_from <= ?"); params.add(LocalDate.parse(f.end())); }
                        }
                        default -> {
                            // natural order is not SQL collation: map the bounds onto the column's stored ranks (one scan of its distinct values)
                            int[] r = rankBounds(k, f.start(), f.end());
                            if (r == null) sql.add("1 = 0");
                            else { sql.add("r_" + k + " BETWEEN ? AND ?"); params.add(r[0]); params.add(r[1]); }
                        }
                    }
                }
            }
        }
        return new Where(sql, params, fuzzy);
    }

    private int[] rankBounds(String k, String start, String end) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (Value v : db.sql("SELECT c_" + k + ", r_" + k + " FROM dash_row WHERE r_" + k + " IS NOT NULL")
                .query((rs, i) -> new Value(rs.getString(1), rs.getString(2))).list()) {
            int rank = Integer.parseInt(v.label());
            if ((start == null || NATURAL.compare(v.value(), start) >= 0) && (end == null || NATURAL.compare(v.value(), end) <= 0)) {
                lo = Math.min(lo, rank);
                hi = Math.max(hi, rank);
            }
        }
        return lo > hi ? null : new int[]{lo, hi};
    }

    private static String orderBy(View view) {
        String k = view.sortKey(), dir = "desc".equals(view.sortDir()) ? " DESC" : "";
        // ord is the default order (EoL date ascending, unknown last, then product and cycle), and every tie keeps it
        if (k == null || (k.equals("eol") && dir.isEmpty())) return " ORDER BY ord";
        return switch (type(k)) {
            case "number" -> " ORDER BY c_" + k + dir + ", ord";
            case "date" -> " ORDER BY CASE WHEN eol_from IS NULL THEN 1 ELSE 0 END, eol_from" + dir + ", ord";
            default -> " ORDER BY CASE WHEN r_" + k + " IS NULL THEN 1 ELSE 0 END, r_" + k + dir + ", ord";
        };
    }

    private static final String COLUMNS = "product, cycle, c_purl, c_ecosystem, c_dependency, c_version, c_eol, c_status, status_css, c_repos, c_branches, "
            + "c_context, c_cycle, matching, links";

    public Page page(String viewText, boolean usedOnly) {
        View view = parse(viewText);
        fresh();
        Where base = where(view, usedOnly, false), w = where(view, usedOnly, true);
        int total = count(base);
        String order = orderBy(view);
        if (w.fuzzy().isEmpty()) {
            int matching = count(w);
            int pages = Math.max(1, (matching + view.size() - 1) / view.size());
            int page = Math.min(pages, view.page());
            List<Object> params = new ArrayList<>(w.params());
            params.add(view.size());
            params.add((page - 1) * view.size());
            List<Row> rows = db.sql("SELECT " + COLUMNS + " FROM dash_row" + w.clause() + order + " LIMIT ? OFFSET ?").params(params)
                    .query((rs, i) -> row(rs)).list();
            return new Page(total, matching, page, pages, view.size(), rows);
        }
        // shortcut: fuzzy (typo-tolerant) matching has no SQL form, so it scans the ordered ord + column values the other filters left
        List<String> cols = w.fuzzy().stream().map(f -> expr(f.key())).distinct().toList();
        List<Integer> ords = new ArrayList<>();
        db.sql("SELECT ord, " + String.join(", ", cols) + " FROM dash_row" + w.clause() + order).params(w.params()).query(rs -> {
            for (Filter f : w.fuzzy()) if (!fuzzyMatches(rs.getString(cols.indexOf(expr(f.key())) + 2).toLowerCase(Locale.ROOT), f.text().strip().toLowerCase(Locale.ROOT))) return;
            ords.add(rs.getInt(1));
        });
        int matching = ords.size();
        int pages = Math.max(1, (matching + view.size() - 1) / view.size());
        int page = Math.min(pages, view.page());
        List<Integer> slice = ords.subList((page - 1) * view.size(), Math.min(matching, page * view.size()));
        List<Row> rows = new ArrayList<>();
        if (!slice.isEmpty()) {
            Map<Integer, Row> byOrd = new LinkedHashMap<>();
            db.sql("SELECT ord, " + COLUMNS + " FROM dash_row WHERE ord IN (" + String.join(", ", java.util.Collections.nCopies(slice.size(), "?")) + ")")
                    .params(new ArrayList<Object>(slice)).query(rs -> {
                        byOrd.put(rs.getInt("ord"), row(rs));
                    });
            slice.forEach(o -> rows.add(byOrd.get(o)));
        }
        return new Page(total, matching, page, pages, view.size(), rows);
    }

    private int count(Where w) {
        return db.sql("SELECT count(*) FROM dash_row" + w.clause()).params(w.params()).query(Integer.class).single();
    }

    /** A column's distinct values over the whole table (not the filtered result), in the browser's ascending sort order. */
    public List<Value> values(String key, boolean usedOnly) {
        if (!KEYS.contains(key)) throw new IllegalArgumentException("Unknown column");
        fresh();
        String where = usedOnly ? " WHERE c_repos > 0" : "";
        String sql = switch (type(key)) {
            case "number" -> "SELECT CONCAT('', c_" + key + ") FROM dash_row" + where + " GROUP BY c_" + key + " ORDER BY c_" + key;
            case "date" -> "SELECT c_eol FROM dash_row" + where + " GROUP BY c_eol ORDER BY CASE WHEN c_eol = '' THEN 1 ELSE 0 END, c_eol";
            default -> "SELECT c_" + key + " FROM dash_row" + where + " GROUP BY c_" + key
                    + " ORDER BY CASE WHEN MIN(r_" + key + ") IS NULL THEN 1 ELSE 0 END, MIN(r_" + key + "), MIN(ord)";
        };
        return db.sql(sql).query((rs, i) -> {
            String v = rs.getString(1);
            String label = v.isEmpty() && key.equals("eol") ? "Not published" : v.isEmpty() && key.equals("version") ? "Not installed" : v;
            return new Value(v, label);
        }).list();
    }

    private static Row row(ResultSet rs) throws SQLException {
        Links l;
        try {
            l = JSON.readValue(rs.getString("links"), Links.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("dash_row links are not JSON", e);
        }
        String p = rs.getString("product"), c = rs.getString("cycle");
        String href = UriComponentsBuilder.fromPath("/dependencies/{p}").queryParam("version", "{c}").buildAndExpand(p, c).encode().toUriString();
        return new Row(p, c, href, rs.getString("c_purl"), rs.getString("c_ecosystem"), rs.getString("c_dependency"), rs.getString("c_version"),
                rs.getString("c_eol"), rs.getString("c_status"), rs.getString("status_css"), rs.getInt("c_repos"), rs.getInt("c_branches"),
                rs.getString("c_context"), rs.getString("c_cycle"), rs.getString("matching"), l.evidence(), l.sources(), l.sboms());
    }

    // ---- app.js fuzzyMatches, ported literally ------------------------------------------------------

    private static final Pattern WORD_SPLIT = Pattern.compile("[^\\p{L}\\p{N}.]+", Pattern.UNICODE_CHARACTER_CLASS);

    static int editDistance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
            for (int j = 1; j <= b.length(); j++) {
                d[i][j] = i == 0 ? j : Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
            }
        }
        return d[a.length()][b.length()];
    }

    /** v and q already lower-cased. */
    static boolean fuzzyMatches(String v, String q) {
        List<String> words = Arrays.stream(WORD_SPLIT.split(v)).filter(s -> !s.isEmpty()).toList();
        for (String w : q.split("\\s+")) {
            if (w.isEmpty() || v.contains(w)) continue;
            int allowed = w.length() <= 3 ? 0 : w.length() <= 6 ? 1 : 2;
            if (words.stream().noneMatch(x -> editDistance(w, x) <= allowed || editDistance(w, x.substring(0, Math.min(x.length(), w.length()))) <= allowed)) return false;
        }
        return true;
    }
}
