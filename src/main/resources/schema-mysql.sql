-- MySQL 8.0.17+ schema (profile "mysql"). Same tables and columns as schema.sql (H2), except:
-- * utf8mb4_0900_bin: exact, case- and trailing-space-sensitive text identities.
-- * DATETIME(6) holds UTC; the app converts explicitly, so server, session and JVM time zones do not matter.
-- * Keys too long for InnoDB's 3072-byte index limit (repo_url+branch, product+purl) are enforced through a stored
--   generated column: SHA-256 of each part, concatenated (fixed width, so unambiguous). Lookups still compare the
--   full columns, so a digest collision is rejected as a duplicate key (the write fails) and never joins identities.
-- * Referential constraints are written at table level (MySQL ignores the inline column form).

CREATE TABLE IF NOT EXISTS product (
    name VARCHAR(200) NOT NULL PRIMARY KEY,
    label VARCHAR(300) NOT NULL,
    html_link VARCHAR(1000),
    synced_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS product_identifier (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product VARCHAR(200) NOT NULL,
    purl VARCHAR(1000) NOT NULL,
    identity_key BINARY(64) AS (CONCAT(UNHEX(SHA2(product, 256)), UNHEX(SHA2(purl, 256)))) STORED NOT NULL,
    UNIQUE KEY uq_product_identifier (identity_key),
    KEY ix_product_identifier_product (product)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS sync_status (
    job VARCHAR(50) NOT NULL PRIMARY KEY,
    attempted_at DATETIME(6),
    succeeded_at DATETIME(6),
    ok BOOLEAN,
    message VARCHAR(4000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS repo_branch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    repo_url VARCHAR(1000) NOT NULL,
    branch VARCHAR(500) NOT NULL,
    repo_name VARCHAR(300) NOT NULL,
    provider VARCHAR(50) NOT NULL,
    sbom_url VARCHAR(2000) NOT NULL,
    identity_key BINARY(64) AS (CONCAT(UNHEX(SHA2(repo_url, 256)), UNHEX(SHA2(branch, 256)))) STORED NOT NULL,
    UNIQUE KEY uq_repo_branch (identity_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    branch_id BIGINT NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    sbom_url VARCHAR(2000) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    CONSTRAINT fk_snapshot_branch FOREIGN KEY (branch_id) REFERENCES repo_branch (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS dependency (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    snapshot_id BIGINT NOT NULL,
    name VARCHAR(500) NOT NULL,
    version VARCHAR(200),
    purl VARCHAR(1000),
    CONSTRAINT fk_dependency_snapshot FOREIGN KEY (snapshot_id) REFERENCES snapshot (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- Validated JSON-upload entry (canonical form) of an inline-inventory snapshot; snapshots written by earlier versions from fetched SBOMs have no row here.
CREATE TABLE IF NOT EXISTS snapshot_inventory (
    snapshot_id BIGINT NOT NULL PRIMARY KEY,
    payload LONGTEXT NOT NULL,
    CONSTRAINT fk_snapshot_inventory_snapshot FOREIGN KEY (snapshot_id) REFERENCES snapshot (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS import_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ran_at DATETIME(6) NOT NULL,
    source VARCHAR(200) NOT NULL,
    ok BOOLEAN NOT NULL,
    rows_total INT NOT NULL,
    new_snapshots INT NOT NULL,
    message VARCHAR(4000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
