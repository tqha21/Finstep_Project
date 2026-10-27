-- Synthetic, non-login accounts in the disposable analytics test database only.
INSERT INTO users(id,email,password_hash,full_name,created_at,email_verified_at,status)
SELECT ('00000000-0000-0000-0000-'||lpad(n::text,12,'0'))::uuid,
 'metrics-'||n||'@example.test','not-a-login-hash','Synthetic metrics fixture',
 CASE WHEN n<=8 THEN '2026-10-05T17:30:00Z'::timestamptz
      WHEN n<=13 THEN '2026-10-20T17:30:00Z'::timestamptz ELSE '2026-10-26T16:30:00Z'::timestamptz END,
 CASE WHEN n<=8 THEN '2026-10-05T17:31:00Z'::timestamptz
      WHEN n<=13 THEN '2026-10-20T17:31:00Z'::timestamptz ELSE '2026-10-26T16:31:00Z'::timestamptz END,
 CASE WHEN n=8 THEN 'DISABLED' ELSE 'ACTIVE' END
FROM generate_series(1,17) n;
INSERT INTO wallets(id,user_id,name,type,created_at)
SELECT u.id,u.id,'Synthetic cash','CASH',u.created_at+interval '5 minutes' FROM users u;
INSERT INTO wallets(id,user_id,name,type,created_at)
SELECT ('10000000'||substr(u.id::text,9))::uuid,u.id,'Synthetic bank','BANK',u.created_at+interval '5 minutes' FROM users u;
INSERT INTO categories(id,user_id,name,type,created_at)
SELECT u.id,u.id,'Synthetic expense','EXPENSE',u.created_at FROM users u;

-- Created-at defines action time; occurred-at deliberately predates signup.
INSERT INTO transactions(user_id,wallet_id,category_id,type,amount,note,occurred_at,created_at,deleted_at)
SELECT u.id,u.id,u.id,'EXPENSE',123456789,'PRIVATE-METRICS-SENTINEL', '1999-01-01T00:00:00Z',
 u.created_at + CASE right(u.id::text,1)
   WHEN '1' THEN interval '10 minutes' WHEN '2' THEN interval '1 day'
   WHEN '3' THEN interval '7 days' WHEN '4' THEN interval '7 days 23 hours 30 minutes'
   WHEN '5' THEN interval '30 minutes' WHEN '7' THEN interval '2 hours' WHEN '8' THEN interval '3 hours' END,
 CASE WHEN right(u.id::text,1)='5' THEN '2026-10-25T00:00:00Z'::timestamptz END
FROM users u WHERE u.id::text IN (SELECT '00000000-0000-0000-0000-'||lpad(n::text,12,'0') FROM generate_series(1,8) n WHERE n<>6);

-- Bangkok D7 starts at 17:00 UTC on Oct 12 and ends at 17:00 UTC on Oct 13.
INSERT INTO transactions(user_id,wallet_id,category_id,type,amount,occurred_at,created_at,deleted_at)
SELECT u.id,u.id,u.id,'EXPENSE',1,'2026-01-01T00:00:00Z',
 CASE WHEN right(u.id::text,1)='1' THEN '2026-10-12T17:00:00Z'::timestamptz ELSE '2026-10-13T01:00:00Z'::timestamptz END,
 CASE WHEN right(u.id::text,1)='5' THEN '2026-10-25T00:00:00Z'::timestamptz END
FROM users u WHERE u.id IN ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000005');
INSERT INTO transfers(user_id,from_wallet_id,to_wallet_id,amount,occurred_at,created_at)
SELECT u.id,u.id,('10000000'||substr(u.id::text,9))::uuid,987654321,'2000-01-01T00:00:00Z',
 CASE WHEN right(u.id::text,1)='2' THEN '2026-10-13T16:59:59Z'::timestamptz ELSE '2026-10-13T01:00:00Z'::timestamptz END
FROM users u WHERE u.id IN ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000006');

-- Immature accounts must not become zero-retention failures or partial activation denominators.
INSERT INTO transactions(user_id,wallet_id,category_id,type,amount,occurred_at,created_at)
SELECT u.id,u.id,u.id,'EXPENSE',1,'2026-10-21T00:00:00Z',u.created_at+interval '1 hour'
FROM users u WHERE u.id='00000000-0000-0000-0000-000000000009';
