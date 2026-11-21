package dev.eolledger;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpTest {

    @Test
    void stalledBodyAfterHeadersHitsTheOverallDeadline() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> Http.get(URI.create(TestSupport.base() + "/stall"), Duration.ofSeconds(1), 1_000_000))
                .isInstanceOf(Http.FetchException.class)
                .hasMessageContaining("timed out");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void bodyLargerThanCapIsRejected() {
        assertThatThrownBy(() -> Http.get(URI.create(TestSupport.base() + "/huge"), Duration.ofSeconds(5), 10_000))
                .isInstanceOf(Http.FetchException.class)
                .hasMessageContaining("larger than 10000 bytes");
    }

    @Test
    void normalBodyIsReturned() throws Exception {
        TestSupport.REPLIES.put("/ok.json", TestSupport.Reply.ok("{\"a\":1}"));
        assertThat(new String(Http.get(URI.create(TestSupport.base() + "/ok.json"), Duration.ofSeconds(5), 1000))).isEqualTo("{\"a\":1}");
    }
}
