# Lifecycle-linked demo modules: native resolver evidence

`lifecycle-maven` (enterprise-suite) and `lifecycle-uv` (web-platform) were appended to every branch of
`../demo-dependency-inventory.json` on 2026-11-21 so the demo has packages whose purls are real
endoflife.date identifiers (`pkg:maven/org.springframework/spring-core`, `pkg:pypi/django`).
Each module's rows were generated from the native tree output shipped next to its manifest here;
the original modules' rows and evidence paths are unchanged and still point into the separate demo archive.

| Branch | Module | Manifest pins | Native command | Tool |
|---|---|---|---|---|
| enterprise-suite `main` | lifecycle-maven | spring-core 7.0.11 | `mvn -B dependency:tree -DoutputType=json` (and plain text) | Apache Maven 3.8.7, maven-dependency-plugin 3.8.1, Java 21 |
| enterprise-suite `release/legacy` | lifecycle-maven | spring-core 5.3.39 | same | same |
| web-platform `main` | lifecycle-uv | Django==5.2.19 | `uv lock --python 3.11 && uv sync --frozen --python 3.11 && uv tree --frozen --no-dedupe` | uv 0.11.16, CPython 3.11.15 |
| web-platform `release/legacy` | lifecycle-uv | Django==3.2.25 | same | same |

The inventory carries no EoL dates; status comes only from a catalogue sync.
