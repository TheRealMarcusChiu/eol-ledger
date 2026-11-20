package dev.eolledger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Maps an SBOM package to an endoflife.date product and release cycle.
 * Precedence: exact normalized purl listed by exactly one product, then explicit mapping
 * (normalized purl or exact package name), then — only for packages without a purl — a package
 * name equal to a product name. Anything else stays unmatched.
 */
public final class Matcher {

    public record Match(String product, String cycle, String evidence) {
    }

    private static final Pattern NUMERIC = Pattern.compile("\\d+(\\.\\d+)*");
    private static final Pattern LEADING_NUMERIC = Pattern.compile("^[vV]?(\\d+(?:\\.\\d+)*)");

    private final Map<String, Set<String>> productsByPurl;
    private final Map<String, List<String>> cyclesByProduct;
    private final Map<String, String> mappings;

    public Matcher(Map<String, Set<String>> productsByPurl, Map<String, List<String>> cyclesByProduct, Map<String, String> mappings) {
        this.productsByPurl = productsByPurl;
        this.cyclesByProduct = cyclesByProduct;
        this.mappings = mappings;
    }

    public Match match(String name, String version, String purl) {
        String norm = normalizePurl(purl);
        String product = null;
        String evidence;
        Set<String> listed = norm == null ? Set.of() : productsByPurl.getOrDefault(norm, Set.of());
        if (listed.size() == 1) {
            product = listed.iterator().next();
            evidence = "purl " + norm + " is an endoflife.date identifier of " + product;
        } else if (norm != null && mappings.containsKey(norm)) {
            product = mappings.get(norm);
            evidence = "explicit mapping " + norm + " = " + product;
        } else if (name != null && mappings.containsKey(name.toLowerCase(Locale.ROOT))) {
            product = mappings.get(name.toLowerCase(Locale.ROOT));
            evidence = "explicit mapping by package name " + name + " = " + product;
        } else if (listed.size() > 1) {
            return new Match(null, null, "purl " + norm + " is listed by several products " + String.join(", ", listed.stream().sorted().toList())
                    + "; add an explicit mapping to choose one");
        } else if (norm == null && name != null && cyclesByProduct.containsKey(name.toLowerCase(Locale.ROOT))) {
            product = name.toLowerCase(Locale.ROOT);
            evidence = "package has no purl; its name equals product " + product;
        } else {
            return new Match(null, null, norm == null
                    ? "No catalogue identifier or mapping for package name " + name + " (no purl)"
                    : "No catalogue identifier or mapping for " + norm);
        }
        String cycle = matchCycle(version, cyclesByProduct.getOrDefault(product, List.of()));
        if (cycle == null) {
            evidence += "; no release cycle of " + product + " matches version " + version;
        } else {
            evidence += "; version " + version + " falls in cycle " + cycle;
        }
        return new Match(product, cycle, evidence);
    }

    /** pkg:type/namespace/name with version, qualifiers and subpath removed; lower-cased and percent-decoded. */
    public static String normalizePurl(String purl) {
        if (purl == null) return null;
        String p = purl.trim();
        if (!p.regionMatches(true, 0, "pkg:", 0, 4) || p.indexOf('/') < 0) return null;
        p = p.replaceAll("[?#].*$", "");
        int slash = p.lastIndexOf('/');
        int at = p.indexOf('@', slash);
        if (at > 0) p = p.substring(0, at);
        p = URLDecoder.decode(p.replace("+", "%2B"), StandardCharsets.UTF_8);
        return p.toLowerCase(Locale.ROOT);
    }

    /** Longest numeric cycle whose dot components are a prefix of the version's numeric components. */
    public static String matchCycle(String version, Collection<String> cycles) {
        if (version == null) return null;
        var m = LEADING_NUMERIC.matcher(version.trim());
        if (!m.find()) return null;
        List<String> v = Arrays.asList(m.group(1).split("\\."));
        String best = null;
        int bestLen = 0;
        for (String c : cycles) {
            if (!NUMERIC.matcher(c).matches()) continue;
            List<String> parts = Arrays.asList(c.split("\\."));
            if (parts.size() > v.size() || parts.size() <= bestLen) continue;
            if (sameNumbers(parts, v.subList(0, parts.size()))) {
                best = c;
                bestLen = parts.size();
            }
        }
        return best;
    }

    private static boolean sameNumbers(List<String> a, List<String> b) {
        List<String> x = new ArrayList<>();
        List<String> y = new ArrayList<>();
        a.forEach(s -> x.add(s.replaceFirst("^0+(?=\\d)", "")));
        b.forEach(s -> y.add(s.replaceFirst("^0+(?=\\d)", "")));
        return x.equals(y);
    }
}
