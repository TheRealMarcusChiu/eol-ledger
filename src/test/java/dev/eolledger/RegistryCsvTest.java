package dev.eolledger;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistryCsvTest {

    static final String HEADER = "repo_name,repo_url,provider,branch,sbom_url\n";
    final List<EolProperties.Host> hosts = List.of(
            new EolProperties.Host("artifactory.example.com", "t", false),
            new EolProperties.Host("127.0.0.1", null, true),
            new EolProperties.Host("plain.example.com", null, true));

    List<RegistryCsv.Row> parse(String body) {
        return RegistryCsv.parse(HEADER + body, hosts);
    }

    String errorOf(String body) {
        try {
            parse(body);
        } catch (RegistryCsv.InvalidCsvException e) {
            return String.join("\n", e.errors());
        }
        throw new AssertionError("expected rejection");
    }

    @Test
    void parsesQuotedRowsAndNormalizesRepoUrl() {
        var rows = parse("\"Payments, API\",https://GitLab.com/acme/payments.git/,GitLab,release/2.x,https://artifactory.example.com/a/sbom.json\n");
        assertThat(rows).containsExactly(new RegistryCsv.Row("Payments, API", "https://gitlab.com/acme/payments", "gitlab", "release/2.x",
                "https://artifactory.example.com/a/sbom.json"));
    }

    @Test
    void acceptsAzureDevOpsAndBundledSamples() {
        var rows = parse("Ledger,https://dev.azure.com/org/Proj/_git/ledger,Azure DevOps,main,classpath:samples/ledger.cdx.json\n");
        assertThat(rows.get(0).provider()).isEqualTo("azure-devops");
        assertThat(rows.get(0).repoUrl()).isEqualTo("https://dev.azure.com/org/Proj/_git/ledger");
    }

    @Test
    void requiresExactHeader() {
        assertThatThrownBy(() -> RegistryCsv.parse("name,url\nx,y\n", hosts))
                .isInstanceOf(RegistryCsv.InvalidCsvException.class)
                .hasMessageContaining("repo_name,repo_url,provider,branch,sbom_url");
    }

    @Test
    void rejectsUnsafeSbomUrls() {
        assertThat(errorOf("a,https://github.com/a/b,github,main,https://evil.example.net/sbom.json\n")).contains("not in the SBOM host allowlist");
        assertThat(errorOf("a,https://github.com/a/b,github,main,file:///etc/passwd\n")).contains("line 2");
        assertThat(errorOf("a,https://github.com/a/b,github,main,classpath:samples/../application.properties\n")).contains("classpath:samples/");
        assertThat(errorOf("a,https://github.com/a/b,github,main,classpath:application.properties\n")).contains("classpath:samples/");
        assertThat(errorOf("a,https://github.com/a/b,github,main,https://user:pw@artifactory.example.com/x.json\n")).contains("credentials");
        assertThat(errorOf("a,https://github.com/a/b,github,main,https://artifactory.example.com/x.json?token=abc\n")).contains("query");
        assertThat(errorOf("a,https://github.com/a/b,github,main,http://artifactory.example.com/x.json\n")).contains("HTTPS");
        // allow-http is honoured only for loopback hosts
        assertThat(errorOf("a,https://github.com/a/b,github,main,http://plain.example.com/x.json\n")).contains("HTTPS");
        assertThat(parse("a,https://github.com/a/b,github,main,http://127.0.0.1:8080/x.json\n")).hasSize(1);
    }

    @Test
    void errorsNeverEchoCredentials() {
        assertThat(errorOf("a,https://github.com/a/b,github,main,https://user:s3cr3t@artifactory.example.com/x.json\n")).doesNotContain("s3cr3t");
        assertThat(errorOf("a,https://bob:hunter2@github.com/a/b,github,main,classpath:samples/x.json\n")).doesNotContain("hunter2");
    }

    @Test
    void rejectsUnsafeRepoUrlsBadProviderBlankBranchAndDuplicates() {
        assertThat(errorOf("a,javascript:alert(1),github,main,classpath:samples/x.json\n")).contains("repo_url");
        assertThat(errorOf("a,https://github.com/a/b,sourceforge,main,classpath:samples/x.json\n")).contains("provider");
        assertThat(errorOf("a,https://github.com/a/b,github,,classpath:samples/x.json\n")).contains("branch");
        assertThat(errorOf("a,https://github.com/a/b,github,main,classpath:samples/x.json\nb,https://github.com/a/b.git,github,main,classpath:samples/y.json\n"))
                .contains("duplicate");
        assertThat(errorOf("a,https://github.com/a/b,github\n")).contains("5 columns");
    }

    @Test
    void reportsAllBadRowsAtOnce() {
        assertThat(errorOf("a,https://github.com/a/b,nope,main,classpath:samples/x.json\nb,ftp://x/y,github,main,classpath:samples/x.json\n"))
                .contains("line 2").contains("line 3");
    }

    @Test
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> RegistryCsv.parse(HEADER, hosts)).hasMessageContaining("no data rows");
    }
}
