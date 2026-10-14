-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

CREATE SCHEMA IF NOT EXISTS "vernac";

CREATE TABLE "vernac"."@delivery" (
    "attempts" INTEGER NOT NULL,
    "claimToken" UUID,
    "completedAt" TIMESTAMPTZ(6),
    "createdAt" TIMESTAMPTZ(6) NOT NULL,
    "eventId" UUID NOT NULL,
    "eventType" TEXT NOT NULL,
    "id" UUID NOT NULL,
    "lastAttemptAt" TIMESTAMPTZ(6),
    "lastError" TEXT,
    "leaseUntil" TIMESTAMPTZ(6),
    "listenerId" TEXT NOT NULL,
    "nextAttemptAt" TIMESTAMPTZ(6) NOT NULL,
    "status" TEXT NOT NULL,
    CONSTRAINT "@pk:@delivery" PRIMARY KEY ("id")
);

ALTER TABLE "vernac"."@delivery" ADD CONSTRAINT "@check:attempts" CHECK ("attempts" >= 0);

ALTER TABLE "vernac"."@delivery" ADD CONSTRAINT "@check:claim" CHECK (("status" = 'PROCESSING' AND "claimToken" IS NOT NULL AND "leaseUntil" IS NOT NULL) OR ("status" <> 'PROCESSING' AND "claimToken" IS NULL AND "leaseUntil" IS NULL));

ALTER TABLE "vernac"."@delivery" ADD CONSTRAINT "@check:status" CHECK ("status" IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'BLOCKED'));

ALTER TABLE "vernac"."@delivery" ADD CONSTRAINT "@unique:delivery" UNIQUE ("eventType", "eventId", "listenerId");
