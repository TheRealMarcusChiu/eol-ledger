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

CREATE TABLE IF NOT EXISTS import_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ran_at TIMESTAMP WITH TIME ZONE NOT NULL,
    source VARCHAR(200) NOT NULL,
    ok BOOLEAN NOT NULL,
    rows_total INT NOT NULL,
    new_snapshots INT NOT NULL,
    message VARCHAR(4000)
);
