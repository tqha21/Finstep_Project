\set ON_ERROR_STOP on
BEGIN;
-- Only UUIDs still present in the authoritative source checkpoint may survive.
CREATE TEMP TABLE current_accounts (LIKE users);
\copy current_accounts(id,email,password_hash,full_name,currency,timezone,status,email_verified_at,created_at,auth_version) FROM '/tmp/finstep-current-accounts.csv' WITH (FORMAT csv)
CREATE TEMP TABLE removed_accounts AS SELECT id FROM users WHERE id NOT IN (SELECT id FROM current_accounts);
DELETE FROM feedback WHERE user_id IN (SELECT id FROM removed_accounts);
DELETE FROM budget_items WHERE user_id IN (SELECT id FROM removed_accounts);
DELETE FROM budgets WHERE user_id IN (SELECT id FROM removed_accounts);
DELETE FROM idempotency_requests WHERE user_id IN (SELECT id FROM removed_accounts);
DELETE FROM transactions WHERE user_id IN (SELECT id FROM removed_accounts);
DELETE FROM transfers WHERE user_id IN (SELECT id FROM removed_accounts);
DELETE FROM opening_balances WHERE user_id IN (SELECT id FROM removed_accounts);
DELETE FROM wallets WHERE user_id IN (SELECT id FROM removed_accounts);
DELETE FROM categories WHERE user_id IN (SELECT id FROM removed_accounts);
-- Never bring old sessions or one-time recovery credentials back into service.
DELETE FROM identity_tokens;
DELETE FROM spring_session;
DELETE FROM auth_rate_limits;
DELETE FROM users WHERE id IN (SELECT id FROM removed_accounts);
-- Preserve current credentials/profile/status; old backups must not revive an old password or disabled account.
UPDATE users u SET email=c.email,password_hash=c.password_hash,full_name=c.full_name,currency=c.currency,
 timezone=c.timezone,status=c.status,email_verified_at=c.email_verified_at,created_at=c.created_at,
 auth_version=greatest(u.auth_version,c.auth_version)+1
 FROM current_accounts c WHERE u.id=c.id;
SELECT json_build_object('removedAccounts',(SELECT count(*) FROM removed_accounts),
 'survivingAccounts',(SELECT count(*) FROM users),'authenticationStateCleared',true)::text;
COMMIT;
