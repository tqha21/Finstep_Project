CREATE TABLE feedback (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id uuid NOT NULL REFERENCES users(id),
 request_key varchar(128) NOT NULL CHECK (request_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
 request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
 type varchar(10) NOT NULL CHECK (type IN ('BUG','IDEA','OTHER')),
 message varchar(2000) NOT NULL CHECK (length(btrim(message)) BETWEEN 10 AND 2000),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(user_id,request_key)
);
CREATE INDEX feedback_owner_date ON feedback(user_id,created_at DESC,id DESC);
