package dev.eolledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Two independent background jobs; each is off while its cron is "-" (the shipped default). */
@Component
public class Jobs {

    private static final Logger log = LoggerFactory.getLogger(Jobs.class);

    private final CatalogService catalog;
    private final ImportService imports;

    public Jobs(CatalogService catalog, ImportService imports) {
        this.catalog = catalog;
        this.imports = imports;
    }

    @Scheduled(cron = "${eol.jobs.catalog-sync.cron:-}", zone = "${eol.jobs.catalog-sync.zone:UTC}")
    public void catalogSync() {
        catalog.sync();
    }

    @Scheduled(cron = "${eol.jobs.repo-refresh.cron:-}", zone = "${eol.jobs.repo-refresh.zone:UTC}")
    public void repoRefresh() {
        try {
            imports.scheduledRefresh();
        } catch (RuntimeException e) {
            // already recorded in import history; previous snapshots stay, next run retries
            log.warn("Scheduled repository refresh failed: {}", e.getMessage());
        }
    }
}
