package dev.eolledger;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@Controller
public class PagesController {

    private final Ledger ledger;
    private final CatalogService catalog;

    public PagesController(Ledger ledger, CatalogService catalog) {
        this.ledger = ledger;
        this.catalog = catalog;
    }

    /**
     * No query string at all = default view (start today). A present-but-blank start means the user
     * cleared it on purpose, i.e. no lower bound.
     */
    @GetMapping("/")
    String dashboard(@RequestParam(required = false) String start, @RequestParam(required = false) String end,
                     @RequestParam(defaultValue = "asc") String sort, @RequestParam(defaultValue = "false") boolean unused,
                     Model model, HttpServletResponse response) {
        LocalDate today = ledger.today();
        String startText = start == null ? today.toString() : start.trim();
        String endText = end == null ? "" : end.trim();
        model.addAttribute("start", startText);
        model.addAttribute("end", endText);
        model.addAttribute("sort", "desc".equals(sort) ? "desc" : "asc");
        model.addAttribute("unused", unused);
        model.addAttribute("today", today);
        model.addAttribute("status", catalog.status());
        model.addAttribute("catalogEmpty", ledger.catalogEmpty());
        model.addAttribute("isDefault", start == null && end == null);
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
        model.addAttribute("rows", ledger.dashboard(from, to, "desc".equals(sort), unused));
        model.addAttribute("unmatched", ledger.unmatchedCycleCount());
        return "dashboard";
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
