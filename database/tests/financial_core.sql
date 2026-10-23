\set ON_ERROR_STOP on
BEGIN;
-- Fixtures are deliberately rolled back, never installed in the application DB.
INSERT INTO users(id,email,password_hash,full_name) VALUES
 ('00000000-0000-0000-0000-000000000001','a@example.test','test-only','A'),
 ('00000000-0000-0000-0000-000000000002','b@example.test','test-only','B');
DO $$
DECLARE
 u uuid := '00000000-0000-0000-0000-000000000001';
 other_u uuid := '00000000-0000-0000-0000-000000000002';
 a uuid; b uuid; foreign_wallet uuid; income uuid; expense uuid; foreign_category uuid;
 tx uuid; budget uuid; actual numeric;
BEGIN
 INSERT INTO wallets(user_id,name,type) VALUES(u,'Cash','CASH') RETURNING id INTO a;
 INSERT INTO wallets(user_id,name,type) VALUES(u,'Bank','BANK') RETURNING id INTO b;
 INSERT INTO wallets(user_id,name,type) VALUES(other_u,'Other','CASH') RETURNING id INTO foreign_wallet;
 INSERT INTO categories(user_id,name,type) VALUES(u,'Salary','INCOME') RETURNING id INTO income;
 INSERT INTO categories(user_id,name,type) VALUES(u,'Food','EXPENSE') RETURNING id INTO expense;
 INSERT INTO categories(user_id,name,type) VALUES(other_u,'Private','EXPENSE') RETURNING id INTO foreign_category;
 INSERT INTO opening_balances VALUES(a,u,2000000,now());
 INSERT INTO transactions(user_id,wallet_id,category_id,type,amount,occurred_at)
 VALUES(u,a,income,'INCOME',5000000,now());
 INSERT INTO transactions(user_id,wallet_id,category_id,type,amount,occurred_at)
 VALUES(u,a,expense,'EXPENSE',1500000,now()) RETURNING id INTO tx;
 INSERT INTO transfers(user_id,from_wallet_id,to_wallet_id,amount,occurred_at) VALUES(u,a,b,1000000,now());
 SELECT balance INTO actual FROM wallet_balances WHERE wallet_id=a;
 IF actual <> 4500000 THEN RAISE EXCEPTION 'Balance incorrect: %',actual; END IF;
 IF (SELECT sum(balance) FROM wallet_balances WHERE user_id=u) <> 5500000 THEN RAISE EXCEPTION 'Transfer changed total'; END IF;
 UPDATE transactions SET amount=1000000 WHERE id=tx;
 IF (SELECT balance FROM wallet_balances WHERE wallet_id=a) <> 5000000 THEN RAISE EXCEPTION 'Edit not reflected'; END IF;
 UPDATE transactions SET deleted_at=now() WHERE id=tx;
 IF (SELECT balance FROM wallet_balances WHERE wallet_id=a) <> 6000000 THEN RAISE EXCEPTION 'Delete not reflected'; END IF;
 BEGIN
  INSERT INTO transactions(user_id,wallet_id,category_id,type,amount,occurred_at) VALUES(u,foreign_wallet,expense,'EXPENSE',10,now());
  RAISE EXCEPTION 'Cross-user wallet accepted';
 EXCEPTION WHEN foreign_key_violation THEN NULL; END;
 BEGIN
  INSERT INTO transactions(user_id,wallet_id,category_id,type,amount,occurred_at) VALUES(u,a,foreign_category,'EXPENSE',10,now());
  RAISE EXCEPTION 'Cross-user category accepted';
 EXCEPTION WHEN foreign_key_violation THEN NULL; END;
 BEGIN
  INSERT INTO transactions(user_id,wallet_id,category_id,type,amount,occurred_at) VALUES(u,a,income,'EXPENSE',10,now());
  RAISE EXCEPTION 'Wrong category type accepted';
 EXCEPTION WHEN foreign_key_violation THEN NULL; END;
 BEGIN
  INSERT INTO transactions(user_id,wallet_id,category_id,type,amount,occurred_at) VALUES(u,a,expense,'EXPENSE',0,now());
  RAISE EXCEPTION 'Zero amount accepted';
 EXCEPTION WHEN check_violation THEN NULL; END;
 BEGIN
  INSERT INTO transfers(user_id,from_wallet_id,to_wallet_id,amount,occurred_at) VALUES(u,a,a,10,now());
  RAISE EXCEPTION 'Self transfer accepted';
 EXCEPTION WHEN check_violation THEN NULL; END;
 BEGIN
  INSERT INTO transfers(user_id,from_wallet_id,to_wallet_id,amount,occurred_at) VALUES(u,a,foreign_wallet,10,now());
  RAISE EXCEPTION 'Cross-user transfer accepted';
 EXCEPTION WHEN foreign_key_violation THEN NULL; END;
 INSERT INTO budgets(user_id,month) VALUES(u,'2026-10-01') RETURNING id INTO budget;
 INSERT INTO budget_items(user_id,budget_id,category_id,limit_amount) VALUES(u,budget,expense,2000000);
 BEGIN
  INSERT INTO budget_items(user_id,budget_id,category_id,limit_amount) VALUES(u,budget,income,2000000);
  RAISE EXCEPTION 'Income budget accepted';
 EXCEPTION WHEN foreign_key_violation THEN NULL; END;
 BEGIN
  DELETE FROM wallets WHERE id=a;
  RAISE EXCEPTION 'Wallet history deleted';
 EXCEPTION WHEN foreign_key_violation THEN NULL; END;
 RAISE NOTICE 'PASS: balances, transfers, edits, soft deletion, tenant FKs, categories, amounts, budgets, history';
END $$;
ROLLBACK;
