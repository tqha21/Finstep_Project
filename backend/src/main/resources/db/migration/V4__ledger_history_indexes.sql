DROP INDEX transactions_user_date;
CREATE INDEX transactions_user_date ON transactions(user_id,occurred_at DESC,id DESC) WHERE deleted_at IS NULL;
CREATE INDEX transfers_user_date ON transfers(user_id,occurred_at DESC,id DESC) WHERE deleted_at IS NULL;
