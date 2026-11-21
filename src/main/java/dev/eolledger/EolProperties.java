package dev.eolledger;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** All app settings under the {@code eol.} prefix; see README "Configuration". */
@ConfigurationProperties("eol")
public record EolProperties(
        @DefaultValue("") String zone,
        @DefaultValue Catalog catalog,
        @DefaultValue("") List<String> mappings) {

    public ZoneId zoneId() {
        return zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone);
    }

    public EolProperties {
        mappingTable(mappings); // a mistyped mapping fails startup instead of silently matching nothing
    }

    /** "identity=product" entries: a purl (normalized like SBOM purls) or an exact, case-insensitive package name. */
    public Map<String, String> mappingTable() {
        return mappingTable(mappings);
    }

    private static Map<String, String> mappingTable(List<String> mappings) {
        Map<String, String> m = new HashMap<>();
        for (String entry : mappings) {
            if (entry.isBlank()) continue;
            int i = entry.lastIndexOf('=');
            String product = i < 0 ? "" : entry.substring(i + 1).trim();
            String key = i < 0 ? "" : entry.substring(0, i).trim();
            if (key.isEmpty() || product.isEmpty()) {
                throw new IllegalArgumentException("eol.mappings entry \"" + entry.trim() + "\" must have the form <purl or package name>=<product>");
            }
            if (key.regionMatches(true, 0, "pkg:", 0, 4)) {
                String norm = Matcher.normalizePurl(key);
                if (norm == null) throw new IllegalArgumentException("eol.mappings entry \"" + entry.trim() + "\" has a malformed purl");
                key = norm;
            } else {
                key = key.toLowerCase(Locale.ROOT);
            }
            m.put(key, product);
        }
        return m;
    }

    public record Catalog(
            @DefaultValue("https://endoflife.date/api/v1") String baseUrl,
            @DefaultValue("") List<String> products,
            @DefaultValue("60s") Duration timeout,
            @DefaultValue("33554432") long maxBytes) {
    }

}
