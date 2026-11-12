-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

CREATE SCHEMA IF NOT EXISTS "org.vernac.saas.identity.persistence";

CREATE TABLE "org.vernac.saas.identity.persistence"."Identity" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "id" UUID NOT NULL,
    "status" TEXT NOT NULL,
    CONSTRAINT "@pk:Identity" PRIMARY KEY ("id")
);

ALTER TABLE "org.vernac.saas.identity.persistence"."Identity" ADD CONSTRAINT "@check:enum:status" CHECK ("status" IN ('ACTIVE', 'CLOSED', 'DISABLED', 'PENDING_VERIFICATION'));

ALTER TABLE "org.vernac.saas.identity.persistence"."Identity" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."Identity" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);
