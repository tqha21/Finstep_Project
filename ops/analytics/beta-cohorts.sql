-- psql variables are typed/quoted values, never SQL fragments.
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL ROLE finstep_metrics_reader;
SET LOCAL statement_timeout = '30s';
SET LOCAL TIME ZONE 'UTC';
WITH settings AS (
 SELECT :'from_date'::date AS first_day, :'to_date'::date AS last_day,
        :'as_of'::timestamptz AS as_of, :'min_cohort'::int AS min_cohort,
        'Asia/Bangkok'::text AS zone
), accounts AS (
 SELECT u.id,u.created_at,u.email_verified_at,s.as_of,s.zone,
        date_trunc('week',u.created_at AT TIME ZONE s.zone)::date AS week,
        u.created_at + interval '7 days' AS activation_end,
        ((u.created_at AT TIME ZONE s.zone)::date + 7)::timestamp AT TIME ZONE s.zone AS d7_start,
        ((u.created_at AT TIME ZONE s.zone)::date + 8)::timestamp AT TIME ZONE s.zone AS d7_end
 FROM users u CROSS JOIN settings s
 WHERE u.created_at >= s.first_day::timestamp AT TIME ZONE s.zone
   AND u.created_at < s.last_day::timestamp AT TIME ZONE s.zone AND u.created_at < s.as_of
), observations AS (
 SELECT a.*,
   (SELECT min(t.created_at) FROM transactions t
    JOIN wallets w ON w.id=t.wallet_id AND w.user_id=t.user_id
    WHERE t.user_id=a.id AND t.created_at>=a.created_at AND t.created_at<a.activation_end
      AND t.created_at<a.as_of AND w.created_at<=t.created_at) AS first_transaction,
   EXISTS(SELECT 1 FROM transactions t WHERE t.user_id=a.id
          AND t.created_at>=a.d7_start AND t.created_at<a.d7_end AND t.created_at<a.as_of)
   OR EXISTS(SELECT 1 FROM transfers t WHERE t.user_id=a.id
          AND t.created_at>=a.d7_start AND t.created_at<a.d7_end AND t.created_at<a.as_of) AS returned_d7
 FROM accounts a
), aggregates AS (
 SELECT week,count(*) AS registered,
   count(*) FILTER(WHERE email_verified_at<as_of) AS verified,
   count(*) FILTER(WHERE activation_end<=as_of) AS activation_eligible,
   count(*) FILTER(WHERE activation_end<=as_of AND first_transaction IS NOT NULL) AS activated,
   percentile_cont(0.5) WITHIN GROUP(ORDER BY extract(epoch FROM first_transaction-created_at))
     FILTER(WHERE activation_end<=as_of AND first_transaction IS NOT NULL) AS first_seconds,
   count(*) FILTER(WHERE d7_end<=as_of) AS retention_eligible,
   count(*) FILTER(WHERE d7_end<=as_of AND returned_d7) AS retained
 FROM observations GROUP BY week
), weeks AS (
 SELECT generate_series(date_trunc('week',first_day::timestamp),
        date_trunc('week',(last_day-1)::timestamp),interval '7 days')::date AS week FROM settings
), protected AS (
 SELECT w.week,
   CASE WHEN coalesce(a.registered,0)<s.min_cohort THEN jsonb_build_object('suppressed',true)
   ELSE jsonb_build_object('suppressed',false,'registeredAccounts',a.registered,'verifiedAccounts',a.verified,
     'activation7d',CASE WHEN a.activation_eligible<s.min_cohort THEN jsonb_build_object('suppressed',true)
       ELSE jsonb_build_object('suppressed',false,'eligibleAccounts',a.activation_eligible,'activatedAccounts',a.activated,
         'percent',round(100.0*a.activated/a.activation_eligible,2),
         'medianFirstTransactionSeconds',CASE WHEN a.activated>=s.min_cohort THEN round(a.first_seconds::numeric,2) END) END,
     'd7FinancialCreation',CASE WHEN a.retention_eligible<s.min_cohort THEN jsonb_build_object('suppressed',true)
       ELSE jsonb_build_object('suppressed',false,'eligibleAccounts',a.retention_eligible,'returnedAccounts',a.retained,
         'percent',round(100.0*a.retained/a.retention_eligible,2)) END)
   END || jsonb_build_object('weekStarting',w.week) AS value
 FROM weeks w CROSS JOIN settings s LEFT JOIN aggregates a ON a.week=w.week
)
SELECT jsonb_build_object('schemaVersion',1,'timezone',s.zone,'fromDate',s.first_day,'toDateExclusive',s.last_day,
 'asOf',s.as_of,'minimumCohort',s.min_cohort,'cohorts',coalesce((SELECT jsonb_agg(value ORDER BY week) FROM protected),'[]'::jsonb))
FROM settings s;
ROLLBACK;
