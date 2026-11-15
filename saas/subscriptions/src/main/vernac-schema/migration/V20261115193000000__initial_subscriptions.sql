-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

CREATE SCHEMA IF NOT EXISTS "org.vernac.saas.subscriptions";

CREATE TABLE "org.vernac.saas.subscriptions"."Subscription" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@nanoRemainder:period.end" SMALLINT NOT NULL,
    "@nanoRemainder:period.start" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "account" UUID NOT NULL,
    "id" UUID NOT NULL,
    "period.end" TIMESTAMPTZ(6) NOT NULL,
    "period.start" TIMESTAMPTZ(6) NOT NULL,
    "renewal" TEXT NOT NULL,
    "renewalTerms.benefits" TEXT NOT NULL,
    "renewalTerms.currency" TEXT NOT NULL,
    "renewalTerms.interval" TEXT NOT NULL,
    "renewalTerms.plan" TEXT NOT NULL,
    "renewalTerms.price" BIGINT NOT NULL,
    "renewalTerms.revision" TEXT NOT NULL,
    "status" TEXT NOT NULL,
    "terms.benefits" TEXT NOT NULL,
    "terms.currency" TEXT NOT NULL,
    "terms.interval" TEXT NOT NULL,
    "terms.plan" TEXT NOT NULL,
    "terms.price" BIGINT NOT NULL,
    "terms.revision" TEXT NOT NULL,
    CONSTRAINT "@pk:Subscription" PRIMARY KEY ("id")
);

CREATE TABLE "org.vernac.saas.subscriptions"."SubscriptionSettlement" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@nanoRemainder:period.end" SMALLINT NOT NULL,
    "@nanoRemainder:period.start" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "id" UUID NOT NULL,
    "operation" TEXT COLLATE "pg_catalog"."C" NOT NULL,
    "period.end" TIMESTAMPTZ(6) NOT NULL,
    "period.start" TIMESTAMPTZ(6) NOT NULL,
    "subscription" UUID NOT NULL,
    "terms.benefits" TEXT NOT NULL,
    "terms.currency" TEXT NOT NULL,
    "terms.interval" TEXT NOT NULL,
    "terms.plan" TEXT NOT NULL,
    "terms.price" BIGINT NOT NULL,
    "terms.revision" TEXT NOT NULL,
    CONSTRAINT "@pk:SubscriptionSettlement" PRIMARY KEY ("id")
);

ALTER TABLE "org.vernac.saas.subscriptions"."Subscription" ADD CONSTRAINT "@check:enum:renewal" CHECK ("renewal" IN ('CANCELLED', 'ENABLED'));

ALTER TABLE "org.vernac.saas.subscriptions"."Subscription" ADD CONSTRAINT "@check:enum:renewalTerms.currency" CHECK ("renewalTerms.currency" IN ('EUR'));

ALTER TABLE "org.vernac.saas.subscriptions"."Subscription" ADD CONSTRAINT "@check:enum:renewalTerms.interval" CHECK ("renewalTerms.interval" IN ('MONTHLY', 'YEARLY'));

ALTER TABLE "org.vernac.saas.subscriptions"."Subscription" ADD CONSTRAINT "@check:enum:status" CHECK ("status" IN ('ACTIVE', 'AWAITING_PAYMENT', 'ENDED', 'SUSPENDED'));

ALTER TABLE "org.vernac.saas.subscriptions"."Subscription" ADD CONSTRAINT "@check:enum:terms.currency" CHECK ("terms.currency" IN ('EUR'));

ALTER TABLE "org.vernac.saas.subscriptions"."Subscription" ADD CONSTRAINT "@check:enum:terms.interval" CHECK ("terms.interval" IN ('MONTHLY', 'YEARLY'));

ALTER TABLE "org.vernac.saas.subscriptions"."Subscription" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.subscriptions"."Subscription" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.subscriptions"."Subscription" ADD CONSTRAINT "@check:range:@nanoRemainder:period.end" CHECK ("@nanoRemainder:period.end" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.subscriptions"."Subscription" ADD CONSTRAINT "@check:range:@nanoRemainder:period.start" CHECK ("@nanoRemainder:period.start" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.subscriptions"."Subscription" ADD CONSTRAINT "@unique:Subscription:account" UNIQUE ("account");

ALTER TABLE "org.vernac.saas.subscriptions"."SubscriptionSettlement" ADD CONSTRAINT "@check:enum:terms.currency" CHECK ("terms.currency" IN ('EUR'));

ALTER TABLE "org.vernac.saas.subscriptions"."SubscriptionSettlement" ADD CONSTRAINT "@check:enum:terms.interval" CHECK ("terms.interval" IN ('MONTHLY', 'YEARLY'));

ALTER TABLE "org.vernac.saas.subscriptions"."SubscriptionSettlement" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.subscriptions"."SubscriptionSettlement" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.subscriptions"."SubscriptionSettlement" ADD CONSTRAINT "@check:range:@nanoRemainder:period.end" CHECK ("@nanoRemainder:period.end" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.subscriptions"."SubscriptionSettlement" ADD CONSTRAINT "@check:range:@nanoRemainder:period.start" CHECK ("@nanoRemainder:period.start" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.subscriptions"."SubscriptionSettlement" ADD CONSTRAINT "@unique:SubscriptionSettlement:operation" UNIQUE ("operation");

ALTER TABLE "org.vernac.saas.subscriptions"."SubscriptionSettlement" ADD CONSTRAINT "@fk:reference:subscription" FOREIGN KEY ("subscription") REFERENCES "org.vernac.saas.subscriptions"."Subscription" ("id");
