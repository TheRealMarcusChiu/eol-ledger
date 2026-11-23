package dev.eolledger;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** DashboardQuery.NATURAL must order like the shipped browser's Intl.Collator('en', {numeric: true, sensitivity: 'base'}). */
class DashboardCollationTest {

    static List<String> sorted(String... values) {
        List<String> out = new ArrayList<>(List.of(values));
        out.sort(DashboardQuery.NATURAL);
        return out;
    }

    @Test
    void punctuationOrdersAsInIntlDotBeforeSlash() {
        // oracle sort-purl-asc / sort-evidence-asc: ".orm/" sorts before "/" at the same position
        assertThat(sorted("maven / pkg:maven/org.hibernate/hibernate-core@5.6.15.Final",
                "maven / pkg:maven/org.hibernate.orm/hibernate-core@6.6.13.Final")).containsExactly(
                "maven / pkg:maven/org.hibernate.orm/hibernate-core@6.6.13.Final",
                "maven / pkg:maven/org.hibernate/hibernate-core@5.6.15.Final");
    }

    @Test
    void wholeStringOrderNotChunkOrder() {
        // oracle sort-status-asc: "(" (punctuation) sorts before digits, and "Ended" before "Ends"
        assertThat(sorted("Ends in 1 day", "Ended 3 years ago", "Ended (date not published)", "Ended 10 days ago", "Ended 2 days ago"))
                .containsExactly("Ended (date not published)", "Ended 2 days ago", "Ended 3 years ago", "Ended 10 days ago", "Ends in 1 day");
    }

    @Test
    void numericBaseSensitivityStillHolds() {
        assertThat(sorted("3.10", "3.9", "Node.js 10", "Node.js 9", "b", "A")).containsExactly("3.9", "3.10", "A", "b", "Node.js 9", "Node.js 10");
        assertThat(DashboardQuery.NATURAL.compare("React", "react")).isZero();
        assertThat(DashboardQuery.NATURAL.compare("café", "cafe")).isZero();
        assertThat(DashboardQuery.NATURAL.compare("007", "7")).isZero();
        assertThat(DashboardQuery.NATURAL.compare("a b", "ab")).isLessThan(0); // space is not ignorable
    }
}
