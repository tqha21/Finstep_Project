-- Spring Session JDBC PostgreSQL schema; managed by Flyway, not auto initialization.
CREATE TABLE spring_session (
 primary_id char(36) NOT NULL PRIMARY KEY,
 session_id char(36) NOT NULL UNIQUE,
 creation_time bigint NOT NULL,
 last_access_time bigint NOT NULL,
 max_inactive_interval integer NOT NULL,
 expiry_time bigint NOT NULL,
 principal_name varchar(100)
);
CREATE INDEX spring_session_expiry ON spring_session(expiry_time);
CREATE INDEX spring_session_principal ON spring_session(principal_name);
CREATE TABLE spring_session_attributes (
 session_primary_id char(36) NOT NULL REFERENCES spring_session(primary_id) ON DELETE CASCADE,
 attribute_name varchar(200) NOT NULL,
 attribute_bytes bytea NOT NULL,
 PRIMARY KEY(session_primary_id,attribute_name)
);
CREATE TABLE identity_tokens (
 token_hash char(64) PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
 user_id uuid NOT NULL REFERENCES users(id),
 purpose varchar(10) NOT NULL CHECK (purpose IN ('VERIFY','RESET')),
 expires_at timestamptz NOT NULL,
 consumed_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX identity_tokens_user ON identity_tokens(user_id,purpose);
CREATE TABLE auth_rate_limits (
 bucket_hash char(64) NOT NULL,
 window_start bigint NOT NULL,
 attempts integer NOT NULL CHECK (attempts > 0),
 PRIMARY KEY(bucket_hash,window_start)
);

ALTER TABLE users ADD COLUMN auth_version integer NOT NULL DEFAULT 0;
