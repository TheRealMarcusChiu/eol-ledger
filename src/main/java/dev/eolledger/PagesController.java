package dev.eolledger;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Controller
public class PagesController {

    private final Ledger ledger;
    private final CatalogService catalog;

    public PagesController(Ledger ledger, CatalogService catalog) {
        this.ledger = ledger;
        this.catalog = catalog;
    }

    /**
     * Default view: all tracked versions with no date bounds (past, future and unknown EoL dates), used ones only.
     * Legacy start/end/sort links still work: every row is rendered, out-of-range ones carry {@code hidden}, and app.js
     * takes the same bounds as its initial header filter, so clearing it restores the full list without a reload.
     */
    @GetMapping("/")
    String dashboard(@RequestParam(required = false) String start, @RequestParam(required = false) String end,
                     @RequestParam(defaultValue = "asc") String sort, @RequestParam(defaultValue = "false") boolean unused,
                     Model model, HttpServletResponse response) {
        String startText = start == null ? "" : start.trim();
        String endText = end == null ? "" : end.trim();
        boolean desc = "desc".equals(sort);
        model.addAttribute("start", startText);
        model.addAttribute("end", endText);
        model.addAttribute("sort", desc ? "desc" : "asc");
        model.addAttribute("legacy", !startText.isEmpty() || !endText.isEmpty() || desc || unused);
        model.addAttribute("status", catalog.status());
        model.addAttribute("catalogEmpty", ledger.catalogEmpty());
        LocalDate from;
        LocalDate to;
        try {
            from = parse(startText, "Start");
            to = parse(endText, "End");
            if (from != null && to != null && from.isAfter(to)) {
                throw new IllegalArgumentException("Start date " + from + " must be on or before end date " + to + ".");
            }
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            model.addAttribute("error", e.getMessage());
            model.addAttribute("rows", java.util.List.of());
            return "dashboard";
        }
        Ledger.Dashboard dash = ledger.dashboard(null, null, desc, unused);
        List<Ledger.DashRow> rows = dash.rows();
        model.addAttribute("rows", rows);
        model.addAttribute("hiddenRows", rows.stream().filter(r -> !Ledger.inRange(r.cycle().eolFrom(), from, to))
                .map(r -> r.cycle().key()).collect(Collectors.toSet()));
        model.addAttribute("unmatched", dash.unmatched());
        return "dashboard";
    }

    /** Old links (/products/{product}?cycle=...) moved permanently to /dependencies/{product}?version=... */
    @GetMapping("/products/{product}")
    RedirectView legacyProduct(@PathVariable String product, @RequestParam(required = false) String cycle) {
        String url = UriComponentsBuilder.fromPath("/dependencies/{product}")
                .queryParamIfPresent("version", Optional.ofNullable(cycle))
                .buildAndExpand(product).encode().toUriString();
        RedirectView v = new RedirectView(url, true);
        v.setStatusCode(HttpStatus.MOVED_PERMANENTLY);
        return v;
    }

    /** All release cycles (versions) of a dependency, or one version's detail when {@code version} is given. */
    @GetMapping("/dependencies/{product}")
    String product(@PathVariable String product, @RequestParam(name = "version", required = false) String cycle, Model model) {
        Ledger.Product p = ledger.product(product);
        if (p == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown product");
        List<Ledger.Cycle> cycles = ledger.productCycles(product);
        model.addAttribute("product", p);
        model.addAttribute("today", ledger.today());
        if (cycle == null) {
            model.addAttribute("cycles", cycles);
            model.addAttribute("eols", cycles.stream().collect(Collectors.toMap(Ledger.Cycle::name, ledger::eol)));
            model.addAttribute("repoCounts", ledger.repoCountsByCycle(product));
            return "product";
        }
        Ledger.Cycle c = cycles.stream().filter(x -> x.name().equals(cycle)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown release cycle"));
        List<Ledger.Affected> affected = ledger.affected(product, cycle);
        model.addAttribute("cycle", c);
        model.addAttribute("eol", ledger.eol(c));
        model.addAttribute("affected", affected);
        model.addAttribute("repoCount", affected.stream().map(a -> a.branch().repoUrl()).distinct().count());
        model.addAttribute("installed", new TreeSet<>(affected.stream().map(a -> a.dep().displayVersion()).toList()));
        return "cycle";
    }

    @GetMapping("/repos")
    String repos(Model model) {
        List<Ledger.Branch> branches = ledger.branches();
        model.addAttribute("branches", branches);
        Map<Long, String> appIds = new HashMap<>();
        for (Ledger.Branch b : branches) appIds.put(b.id(), ledger.appId(b.snapshotId()));
        model.addAttribute("appIds", appIds);
        model.addAttribute("repoCount", branches.stream().map(Ledger.Branch::repoUrl).distinct().count());
        model.addAttribute("zone", ledger.zone());
        return "repos";
    }

    @GetMapping("/repos/{id}")
    String branch(@PathVariable long id, Model model) {
        Ledger.Branch b = requireBranch(id);
        model.addAttribute("b", b);
        model.addAttribute("zone", ledger.zone());
        model.addAttribute("deps", b.snapshotId() == null ? List.of() : ledger.deps(b.snapshotId()));
        model.addAttribute("inline", b.snapshotId() == null ? null : ledger.inline(b.snapshotId()));
        return "branch";
    }

    @GetMapping("/repos/{id}/deps/{depId}")
    String dependency(@PathVariable long id, @PathVariable long depId, Model model) {
        Ledger.Branch b = requireBranch(id);
        Ledger.Dep d = ledger.dep(id, depId);
        if (d == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown dependency");
        model.addAttribute("b", b);
        model.addAttribute("d", d);
        model.addAttribute("normalizedPurl", Matcher.normalizePurl(d.purl()));
        return "dependency";
    }

    /** Dependency history: a chosen snapshot, or the latest one observed by the end of the as-of day. */
    @GetMapping("/repos/{id}/history")
    String history(@PathVariable long id, @RequestParam(required = false) String asOf, @RequestParam(required = false) Long snapshot,
                   Model model, HttpServletResponse response) {
        Ledger.Branch b = requireBranch(id);
        List<Ledger.Snap> timeline = ledger.timeline(id);
        model.addAttribute("b", b);
        model.addAttribute("timeline", timeline);
        model.addAttribute("zone", ledger.zone());
        String dayText = asOf == null || asOf.isBlank() ? ledger.today().toString() : asOf.trim();
        model.addAttribute("asOf", dayText);
        Ledger.Snap selected;
        if (snapshot != null) {
            selected = timeline.stream().filter(s -> s.id() == snapshot).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown snapshot for this branch"));
            model.addAttribute("asOf", selected.observedAt().atZone(ledger.zone()).toLocalDate().toString());
        } else {
            LocalDate day;
            try {
                day = parse(dayText, "As-of");
            } catch (IllegalArgumentException e) {
                response.setStatus(HttpStatus.BAD_REQUEST.value());
                model.addAttribute("error", e.getMessage());
                model.addAttribute("deps", List.of());
                return "history";
            }
            selected = ledger.asOf(id, day);
        }
        model.addAttribute("selected", selected);
        model.addAttribute("deps", selected == null ? List.of() : ledger.deps(selected.id()));
        model.addAttribute("inline", selected == null ? null : ledger.inline(selected.id()));
        model.addAttribute("first", timeline.isEmpty() ? null : timeline.get(0));
        return "history";
    }

    Ledger.Branch requireBranch(long id) {
        Ledger.Branch b = ledger.branch(id);
        if (b == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown repository branch");
        return b;
    }

    private static LocalDate parse(String text, String label) {
        if (text.isEmpty()) return null;
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(label + " date must be a real calendar date in YYYY-MM-DD form, or blank for no limit.");
        }
    }
}
