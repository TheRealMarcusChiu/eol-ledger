# Release notes: additional demo repositories

The sample grows from 4 to 8 repository-branch entries. Only the closing `]` was moved: the original four entries
keep their exact bytes. Their full compact-JSON SHA-256 (Jackson `writeValueAsString` of each entry, file key order),
pinned before the append and checked again after it by the generator and `AdditionalDemoRepositoriesTest`:

| Entry | SHA-256 |
|---|---|
| enterprise-suite main | e47e7ce798d04652e949535fb9960e922e0bbfc52477cf8f14558de2a45875da |
| enterprise-suite release/legacy | 7ac42082d57de3bd44c06ebfb22132314ab29ff3e2f080813bbb0e10300350ed |
| web-platform main | e4217d50d3a85edb3b56c335aca3c9283302a1fdc76d4d522e8255768a7de1e2 |
| web-platform release/legacy | 8896da912333a59c06abf64b839ef410a09588f36c3c6d01fde7e2cf0e5a96c9 |

Added entries (`provider` other, `demo` true, URL `https://example.invalid/eol-demo/<name>`):

| Entry | appId | Lifecycle-linked cycle (pinned demo catalogue) |
|---|---|---|
| payments-service main | DEMO-1003 | spring-framework 7.0 (not EoL) |
| payments-service release/legacy | DEMO-1003 | spring-framework 5.3 (EoL) |
| reporting-service main | DEMO-1004 | django 5.2 (not EoL) |
| reporting-service release/legacy | DEMO-1004 | django 3.2 (EoL) |

The branch commits, bundle hashes, tool versions, sample hashes before and after, and timestamps come from the generated `run.json`.
