package dev.eolledger;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerUnitTest {

    @Test
    void safeLinkAllowsAtInPathsButNotCredentials() {
        assertThat(Ledger.safeLink("https://www.npmjs.com/package/@angular/core")).isEqualTo("https://www.npmjs.com/package/@angular/core");
        assertThat(Ledger.safeLink("HTTPS://example.com/x")).isEqualTo("HTTPS://example.com/x");
        assertThat(Ledger.safeLink("https://user:pw@example.com/x")).isNull();
        assertThat(Ledger.safeLink("https://user@example.com/x")).isNull();
        assertThat(Ledger.safeLink("javascript:alert(1)")).isNull();
        assertThat(Ledger.safeLink("https:///nohost")).isNull();
        assertThat(Ledger.safeLink("https://exa mple.com")).isNull();
        assertThat(Ledger.safeLink("inline:json-upload")).isNull();
    }

    @Test
    void cycleNamesSortNumerically() {
        List<String> names = new ArrayList<>(List.of("10", "9", "3.10", "3.9", "jammy", "24.04", "09"));
        names.sort(Ledger.NATURAL);
        assertThat(names).containsExactly("3.9", "3.10", "9", "09", "10", "24.04", "jammy");
    }
}
