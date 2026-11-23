CREATE TABLE IF NOT EXISTS product (
    name VARCHAR(200) PRIMARY KEY,
    label VARCHAR(300) NOT NULL,
    html_link VARCHAR(1000),
    synced_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS product_identifier (
    product VARCHAR(200) NOT NULL,
    purl VARCHAR(1000) NOT NULL,
    PRIMARY KEY (product, purl)
);

CREATE TABLE IF NOT EXISTS release_cycle (
    product VARCHAR(200) NOT NULL,
    name VARCHAR(200) NOT NULL,
    label VARCHAR(300),
    release_date DATE,
    is_eol BOOLEAN,
    eol_from DATE,
    latest VARCHAR(200),
    latest_date DATE,
    PRIMARY KEY (product, name)
);

CREATE TABLE IF NOT EXISTS sync_status (
    job VARCHAR(50) PRIMARY KEY,
    attempted_at TIMESTAMP WITH TIME ZONE,
    succeeded_at TIMESTAMP WITH TIME ZONE,
    ok BOOLEAN,
    message VARCHAR(4000)
);

CREATE TABLE IF NOT EXISTS repo_branch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    repo_url VARCHAR(1000) NOT NULL,
    branch VARCHAR(500) NOT NULL,
    repo_name VARCHAR(300) NOT NULL,
    provider VARCHAR(50) NOT NULL,
    sbom_url VARCHAR(2000) NOT NULL,
    UNIQUE (repo_url, branch)
);

CREATE TABLE IF NOT EXISTS snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    branch_id BIGINT NOT NULL REFERENCES repo_branch(id),
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sbom_url VARCHAR(2000) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS dependency (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    snapshot_id BIGINT NOT NULL REFERENCES snapshot(id),
    name VARCHAR(500) NOT NULL,
    version VARCHAR(200),
    purl VARCHAR(1000)
);

-- Validated JSON-upload entry (canonical form) of an inline-inventory snapshot; snapshots written by earlier versions from fetched SBOMs have no row here.
CREATE TABLE IF NOT EXISTS snapshot_inventory (
    snapshot_id BIGINT PRIMARY KEY REFERENCES snapshot(id),
    payload CLOB NOT NULL
);

-- Dashboard read model: one row per catalogue release cycle (used or not) holding each column's cell value exactly as the
-- dashboard filters it (c_*) plus its natural-order rank (r_*, NULL when blank). Rebuilt from the catalogue and the latest
-- snapshots whenever they change; never a source of truth, so it is safe to delete.
CREATE TABLE IF NOT EXISTS dash_row (
    product VARCHAR(200) NOT NULL,
    cycle VARCHAR(200) NOT NULL,
    ord INT NOT NULL,
    c_purl VARCHAR NOT NULL, r_purl INT,
    c_ecosystem VARCHAR NOT NULL, r_ecosystem INT,
    c_dependency VARCHAR NOT NULL, r_dependency INT,
    c_version VARCHAR NOT NULL, r_version INT,
    c_eol VARCHAR(10) NOT NULL, eol_from DATE,
    c_status VARCHAR(200) NOT NULL, r_status INT, status_css VARCHAR(20) NOT NULL,
    c_repos INT NOT NULL,
    c_branches INT NOT NULL,
    c_context VARCHAR NOT NULL, r_context INT,
    c_cycle VARCHAR NOT NULL, r_cycle INT,
    c_evidence VARCHAR NOT NULL, r_evidence INT,
    c_source VARCHAR NOT NULL, r_source INT,
    c_sbom VARCHAR NOT NULL, r_sbom INT,
    matching VARCHAR NOT NULL,
    links CLOB NOT NULL,
    PRIMARY KEY (product, cycle)
);
CREATE UNIQUE INDEX IF NOT EXISTS dash_row_ord ON dash_row (ord);
CREATE INDEX IF NOT EXISTS dash_row_eol ON dash_row (eol_from, ord);
CREATE INDEX IF NOT EXISTS dash_row_repos ON dash_row (c_repos, ord);
CREATE INDEX IF NOT EXISTS dash_row_branches ON dash_row (c_branches, ord);

CREATE TABLE IF NOT EXISTS import_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ran_at TIMESTAMP WITH TIME ZONE NOT NULL,
    source VARCHAR(200) NOT NULL,
    ok BOOLEAN NOT NULL,
    rows_total INT NOT NULL,
    new_snapshots INT NOT NULL,
    message VARCHAR(4000)
);
