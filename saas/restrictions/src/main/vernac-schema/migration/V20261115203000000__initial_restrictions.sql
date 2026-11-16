-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

CREATE SCHEMA IF NOT EXISTS "org.vernac.saas.restrictions";

CREATE TABLE "org.vernac.saas.restrictions"."QuotaBudget" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@nanoRemainder:window.end" SMALLINT NOT NULL,
    "@nanoRemainder:window.start" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "account" UUID NOT NULL,
    "benefit" TEXT NOT NULL,
    "consumed" BIGINT NOT NULL,
    "id" UUID NOT NULL,
    "key" TEXT COLLATE "pg_catalog"."C" NOT NULL,
    "reserved" BIGINT NOT NULL,
    "window.end" TIMESTAMPTZ(6) NOT NULL,
    "window.start" TIMESTAMPTZ(6) NOT NULL,
    CONSTRAINT "@pk:QuotaBudget" PRIMARY KEY ("id")
);

CREATE TABLE "org.vernac.saas.restrictions"."QuotaReservation" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@nanoRemainder:window.end" SMALLINT NOT NULL,
    "@nanoRemainder:window.start" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "account" UUID NOT NULL,
    "amount" BIGINT NOT NULL,
    "benefit" TEXT NOT NULL,
    "budget" UUID NOT NULL,
    "id" UUID NOT NULL,
    "key" TEXT COLLATE "pg_catalog"."C" NOT NULL,
    "status" TEXT NOT NULL,
    "window.end" TIMESTAMPTZ(6) NOT NULL,
    "window.start" TIMESTAMPTZ(6) NOT NULL,
    CONSTRAINT "@pk:QuotaReservation" PRIMARY KEY ("id")
);

ALTER TABLE "org.vernac.saas.restrictions"."QuotaBudget" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.restrictions"."QuotaBudget" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.restrictions"."QuotaBudget" ADD CONSTRAINT "@check:range:@nanoRemainder:window.end" CHECK ("@nanoRemainder:window.end" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.restrictions"."QuotaBudget" ADD CONSTRAINT "@check:range:@nanoRemainder:window.start" CHECK ("@nanoRemainder:window.start" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.restrictions"."QuotaBudget" ADD CONSTRAINT "@unique:QuotaBudget:key" UNIQUE ("key");

ALTER TABLE "org.vernac.saas.restrictions"."QuotaReservation" ADD CONSTRAINT "@check:enum:status" CHECK ("status" IN ('CONFIRMED', 'RELEASED', 'RESERVED'));

ALTER TABLE "org.vernac.saas.restrictions"."QuotaReservation" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.restrictions"."QuotaReservation" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.restrictions"."QuotaReservation" ADD CONSTRAINT "@check:range:@nanoRemainder:window.end" CHECK ("@nanoRemainder:window.end" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.restrictions"."QuotaReservation" ADD CONSTRAINT "@check:range:@nanoRemainder:window.start" CHECK ("@nanoRemainder:window.start" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.restrictions"."QuotaReservation" ADD CONSTRAINT "@unique:QuotaReservation:key" UNIQUE ("key");

ALTER TABLE "org.vernac.saas.restrictions"."QuotaReservation" ADD CONSTRAINT "@fk:reference:budget" FOREIGN KEY ("budget") REFERENCES "org.vernac.saas.restrictions"."QuotaBudget" ("id");
