-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

-- DELIVERY ORIGIN UPGRADE: existing queued jobs remain GLOBAL (NULL tenantId). No tenant attribution is inferred.
-- Stop older workers before enabling tenant publication. Review deployment order and runtime role grants before applying.

ALTER TABLE "vernac"."@delivery" ADD COLUMN "tenantId" UUID;
