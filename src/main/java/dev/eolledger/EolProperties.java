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

    /** "identity=product" entries: a purl (normalized like SBOM purls) or an exact, case-insensitive package name. */
    public Map<String, String> mappingTable() {
        Map<String, String> m = new HashMap<>();
        for (String entry : mappings) {
            int i = entry.lastIndexOf('=');
            if (i <= 0) continue;
            String key = entry.substring(0, i).trim();
            key = key.regionMatches(true, 0, "pkg:", 0, 4) ? Matcher.normalizePurl(key) : key.toLowerCase(Locale.ROOT);
            if (key != null) m.put(key, entry.substring(i + 1).trim());
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
