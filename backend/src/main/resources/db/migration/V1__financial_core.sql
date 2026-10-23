-- PostgreSQL 17. VND-only MVP; amounts use integer dong, never floating point.
CREATE TABLE users (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 email varchar(255) NOT NULL CHECK (email = lower(btrim(email)) AND position('@' in email) > 1),
 password_hash varchar(255) NOT NULL,
 full_name varchar(150) NOT NULL CHECK (length(btrim(full_name)) > 0),
 currency char(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
 timezone varchar(50) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
 status varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
 email_verified_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT users_email_unique UNIQUE(email)
);
CREATE TABLE wallets (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id uuid NOT NULL REFERENCES users(id),
 name varchar(100) NOT NULL CHECK (length(btrim(name)) > 0),
 type varchar(10) NOT NULL CHECK (type IN ('CASH','BANK','EWALLET')),
 currency char(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
 is_archived boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(user_id,id)
);
-- System templates will be copied into user-owned categories during onboarding.
-- This keeps every category FK tenant-safe without nullable-owner exceptions.
CREATE TABLE categories (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id uuid NOT NULL REFERENCES users(id),
 name varchar(100) NOT NULL CHECK (length(btrim(name)) > 0),
 type varchar(10) NOT NULL CHECK (type IN ('INCOME','EXPENSE')),
 is_archived boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(user_id,id,type)
);
CREATE TABLE opening_balances (
 wallet_id uuid PRIMARY KEY,
 user_id uuid NOT NULL,
 amount bigint NOT NULL DEFAULT 0 CHECK (amount BETWEEN -999999999999999 AND 999999999999999),
 effective_at timestamptz NOT NULL,
 FOREIGN KEY(user_id,wallet_id) REFERENCES wallets(user_id,id)
);
CREATE TABLE transactions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id uuid NOT NULL REFERENCES users(id),
 wallet_id uuid NOT NULL,
 category_id uuid NOT NULL,
 type varchar(10) NOT NULL CHECK (type IN ('INCOME','EXPENSE')),
 amount bigint NOT NULL CHECK (amount BETWEEN 1 AND 999999999999999),
 note varchar(1000),
 occurred_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 deleted_at timestamptz,
 FOREIGN KEY(user_id,wallet_id) REFERENCES wallets(user_id,id),
 FOREIGN KEY(user_id,category_id,type) REFERENCES categories(user_id,id,type)
);
CREATE TABLE transfers (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id uuid NOT NULL REFERENCES users(id),
 from_wallet_id uuid NOT NULL,
 to_wallet_id uuid NOT NULL,
 amount bigint NOT NULL CHECK (amount BETWEEN 1 AND 999999999999999),
 note varchar(1000),
 occurred_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 deleted_at timestamptz,
 CHECK (from_wallet_id <> to_wallet_id),
 FOREIGN KEY(user_id,from_wallet_id) REFERENCES wallets(user_id,id),
 FOREIGN KEY(user_id,to_wallet_id) REFERENCES wallets(user_id,id)
);
CREATE TABLE budgets (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id uuid NOT NULL REFERENCES users(id),
 month date NOT NULL CHECK (extract(day FROM month) = 1),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(user_id,month), UNIQUE(user_id,id)
);
CREATE TABLE budget_items (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id uuid NOT NULL,
 budget_id uuid NOT NULL,
 category_id uuid NOT NULL,
 category_type varchar(10) NOT NULL DEFAULT 'EXPENSE' CHECK (category_type = 'EXPENSE'),
 limit_amount bigint NOT NULL CHECK (limit_amount BETWEEN 1 AND 999999999999999),
 FOREIGN KEY(user_id,budget_id) REFERENCES budgets(user_id,id),
 FOREIGN KEY(user_id,category_id,category_type) REFERENCES categories(user_id,id,type),
 UNIQUE(budget_id,category_id)
);
-- One shared namespace for create-transaction and create-transfer retries.
-- The service must insert this and its mutation in ONE transaction, compare
-- request_hash on replay, and persist only a resource ID (no financial payload).
CREATE TABLE idempotency_requests (
 user_id uuid NOT NULL REFERENCES users(id),
 key varchar(128) NOT NULL CHECK (length(key) > 0),
 request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
 resource_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(user_id,key)
);
CREATE INDEX transactions_user_date ON transactions(user_id,occurred_at DESC,id) WHERE deleted_at IS NULL;
CREATE INDEX transactions_wallet ON transactions(user_id,wallet_id) WHERE deleted_at IS NULL;
CREATE INDEX transactions_category_date ON transactions(user_id,category_id,occurred_at) WHERE deleted_at IS NULL;
CREATE INDEX transfers_source ON transfers(user_id,from_wallet_id) WHERE deleted_at IS NULL;
CREATE INDEX transfers_destination ON transfers(user_id,to_wallet_id) WHERE deleted_at IS NULL;
CREATE INDEX wallets_owner ON wallets(user_id);
CREATE INDEX categories_owner ON categories(user_id);
CREATE VIEW wallet_balances AS
 SELECT w.user_id,w.id AS wallet_id,
 coalesce((SELECT o.amount::numeric FROM opening_balances o WHERE o.wallet_id=w.id),0)
 + coalesce((SELECT sum(CASE WHEN t.type='INCOME' THEN t.amount::numeric ELSE -t.amount::numeric END)
 FROM transactions t WHERE t.wallet_id=w.id AND t.deleted_at IS NULL),0)
 + coalesce((SELECT sum(t.amount::numeric) FROM transfers t WHERE t.to_wallet_id=w.id AND t.deleted_at IS NULL),0)
 - coalesce((SELECT sum(t.amount::numeric) FROM transfers t WHERE t.from_wallet_id=w.id AND t.deleted_at IS NULL),0) AS balance
 FROM wallets w;
