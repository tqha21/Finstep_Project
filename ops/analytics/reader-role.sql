-- Apply once as an administrator with CREATEROLE and table-owner grants. Not Flyway.
-- NOLOGIN: report execution assumes this role inside a read-only transaction.
BEGIN;
CREATE ROLE finstep_metrics_reader NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
GRANT USAGE ON SCHEMA public TO finstep_metrics_reader;
GRANT SELECT(id,created_at,email_verified_at) ON users TO finstep_metrics_reader;
GRANT SELECT(id,user_id,created_at) ON wallets TO finstep_metrics_reader;
GRANT SELECT(user_id,wallet_id,created_at) ON transactions TO finstep_metrics_reader;
GRANT SELECT(user_id,created_at) ON transfers TO finstep_metrics_reader;
COMMIT;
