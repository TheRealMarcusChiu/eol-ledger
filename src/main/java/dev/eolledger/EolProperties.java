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
        @DefaultValue Sbom sbom,
        @DefaultValue("") List<String> mappings,
        @DefaultValue("") String refreshCsvPath) {

    public ZoneId zoneId() {
        return zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone);
    }

    /** "identity=product" entries, identity being a normalized purl or exact package name. */
    public Map<String, String> mappingTable() {
        Map<String, String> m = new HashMap<>();
        for (String entry : mappings) {
            int i = entry.lastIndexOf('=');
            if (i > 0) m.put(entry.substring(0, i).trim().toLowerCase(Locale.ROOT), entry.substring(i + 1).trim());
        }
        return m;
    }

    public record Catalog(
            @DefaultValue("https://endoflife.date/api/v1") String baseUrl,
            @DefaultValue("") List<String> products,
            @DefaultValue("60s") Duration timeout,
            @DefaultValue("33554432") long maxBytes) {
    }

    public record Sbom(
            @DefaultValue("") List<Host> hosts,
            @DefaultValue("30s") Duration timeout,
            @DefaultValue("10485760") long maxBytes) {
    }

    /** An allowlisted SBOM host. allowHttp is honoured only for loopback hosts (local dev/test). */
    public record Host(String host, String token, boolean allowHttp) {
    }
}
