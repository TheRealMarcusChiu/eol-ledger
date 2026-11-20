package dev.eolledger;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Template helper, used as {@code ${@view.time(instant)}}: formats in the configured zone. */
@Component("view")
public class ViewHelpers {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
    private final ZoneId zone;

    public ViewHelpers(EolProperties props) {
        this.zone = props.zoneId();
    }

    public String time(Instant t) {
        return t == null ? "" : TIME.format(t.atZone(zone));
    }
}
