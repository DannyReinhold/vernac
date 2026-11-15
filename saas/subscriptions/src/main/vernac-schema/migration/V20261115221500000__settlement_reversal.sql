-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

ALTER TABLE "org.vernac.saas.subscriptions"."SubscriptionSettlement" ADD COLUMN "status" TEXT;

UPDATE "org.vernac.saas.subscriptions"."SubscriptionSettlement" SET "status" = ('SETTLED') WHERE "status" IS NULL;

ALTER TABLE "org.vernac.saas.subscriptions"."SubscriptionSettlement" ALTER COLUMN "status" SET NOT NULL;

ALTER TABLE "org.vernac.saas.subscriptions"."SubscriptionSettlement" ADD CONSTRAINT "@check:enum:status" CHECK ("status" IN ('REVOKED', 'SETTLED'));
