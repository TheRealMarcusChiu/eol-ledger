package dev.eolledger;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Controller
public class ImportController {

    record Run(java.time.Instant ranAt, String source, boolean ok, int rows, int newSnapshots, String message) {
    }

    private final ImportService imports;
    private final CatalogService catalog;
    private final EolProperties props;
    private final JdbcClient db;
    private final String catalogCron;
    private final DbTime time;

    public ImportController(ImportService imports, CatalogService catalog, EolProperties props, JdbcClient db, DbTime time,
                            @Value("${eol.jobs.catalog-sync.cron:-}") String catalogCron) {
        this.imports = imports;
        this.catalog = catalog;
        this.props = props;
        this.db = db;
        this.time = time;
        this.catalogCron = catalogCron;
    }

    @GetMapping("/imports")
    String page(Model model) {
        model.addAttribute("providers", InventoryJson.PROVIDERS);
        model.addAttribute("status", catalog.status());
        model.addAttribute("catalogBase", props.catalog().baseUrl());
        model.addAttribute("catalogMode", "whole catalogue (one request to /products/full)");
        model.addAttribute("catalogCron", catalogCron);
        model.addAttribute("registered", db.sql("SELECT count(*) FROM repo_branch").query(Integer.class).single());
        model.addAttribute("runs", db.sql("SELECT ran_at, source, ok, rows_total, new_snapshots, message FROM import_run ORDER BY id DESC LIMIT 25")
                .query((rs, i) -> new Run(time.read(rs, "ran_at"), rs.getString(2), rs.getBoolean(3),
                        rs.getInt(4), rs.getInt(5), rs.getString(6))).list());
        return "imports";
    }

    @PostMapping("/imports")
    Object upload(@RequestParam(required = false) MultipartFile file, Model model, HttpServletResponse response, RedirectAttributes flash) throws IOException {
        if (file == null || file.isEmpty()) return fail(List.of("Choose a dependency-inventory JSON file to upload."), model, response);
        String text = new String(file.getBytes(), StandardCharsets.UTF_8);
        String source = "upload " + safeName(file.getOriginalFilename());
        try {
            flash.addFlashAttribute("message", imports.importJson(text, source).message());
            return seeOther();
        } catch (ImportService.ImportFailedException e) {
            return fail(e.errors(), model, response);
        }
    }

    @PostMapping("/catalog/sync")
    RedirectView sync(RedirectAttributes flash) {
        CatalogService.SyncResult r = catalog.sync();
        flash.addFlashAttribute(r.ok() ? "message" : "warning", r.message());
        return seeOther();
    }

    @PostMapping("/imports/clear")
    RedirectView clear(@RequestParam(required = false) String confirm, RedirectAttributes flash) {
        if (!"true".equals(confirm)) {
            flash.addFlashAttribute("warning", "Nothing was cleared: tick the confirmation box first.");
            return seeOther();
        }
        try {
            imports.clearAll();
            flash.addFlashAttribute("message", "All imported data and the synced catalogue were cleared.");
        } catch (ImportService.ImportFailedException e) {
            flash.addFlashAttribute("warning", e.getMessage());
        }
        return seeOther();
    }

    @GetMapping("/imports/samples/{name}")
    ResponseEntity<String> sample(@PathVariable String name) {
        if (!ImportService.SAMPLE.equals(name)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(ImportService.sample(name));
    }

    private String fail(List<String> errors, Model model, HttpServletResponse response) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        model.addAttribute("errors", errors);
        return page(model);
    }

    private static String safeName(String name) {
        if (name == null) return "";
        String n = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return n.length() > 80 ? n.substring(0, 80) : n;
    }

    /** POST-redirect-GET with 303 so a browser reload never re-submits the form. */
    private static RedirectView seeOther() {
        RedirectView v = new RedirectView("/imports", true);
        v.setStatusCode(HttpStatus.SEE_OTHER);
        return v;
    }
}
