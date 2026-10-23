-- Templates are copied into tenant-owned rows. Unique key makes retries/concurrency safe.
ALTER TABLE categories ADD COLUMN template_key varchar(50);
ALTER TABLE categories ADD CONSTRAINT categories_owner_template_unique UNIQUE(user_id,template_key);
