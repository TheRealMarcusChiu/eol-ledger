package dev.eolledger;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The background catalogue sync; off while its cron is "-" (the shipped default). */
@Component
public class Jobs {

    private final CatalogService catalog;

    public Jobs(CatalogService catalog) {
        this.catalog = catalog;
    }

    @Scheduled(cron = "${eol.jobs.catalog-sync.cron:-}", zone = "${eol.jobs.catalog-sync.zone:UTC}")
    public void catalogSync() {
        catalog.sync();
    }
}
