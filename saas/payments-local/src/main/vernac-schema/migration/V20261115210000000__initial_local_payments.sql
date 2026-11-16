-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

CREATE SCHEMA IF NOT EXISTS "org.vernac.saas.payments.local";

CREATE TABLE "org.vernac.saas.payments.local"."LocalCustomer" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "id" UUID NOT NULL,
    "mandate" TEXT NOT NULL,
    CONSTRAINT "@pk:LocalCustomer" PRIMARY KEY ("id")
);

CREATE TABLE "org.vernac.saas.payments.local"."LocalPayment" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "amount" BIGINT NOT NULL,
    "checkout" TEXT,
    "cursor" INTEGER NOT NULL,
    "customer" TEXT NOT NULL,
    "customerOperation" UUID NOT NULL,
    "id" UUID NOT NULL,
    "kind" TEXT NOT NULL,
    "mandate" TEXT,
    "refunded" BIGINT NOT NULL,
    "status" TEXT NOT NULL,
    CONSTRAINT "@pk:LocalPayment" PRIMARY KEY ("id")
);

CREATE TABLE "org.vernac.saas.payments.local"."LocalRefund" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "amount" BIGINT NOT NULL,
    "id" UUID NOT NULL,
    "paymentOperation" UUID NOT NULL,
    CONSTRAINT "@pk:LocalRefund" PRIMARY KEY ("id")
);

ALTER TABLE "org.vernac.saas.payments.local"."LocalCustomer" ADD CONSTRAINT "@check:enum:mandate" CHECK ("mandate" IN ('ABSENT', 'VALID'));

ALTER TABLE "org.vernac.saas.payments.local"."LocalCustomer" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.local"."LocalCustomer" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.local"."LocalPayment" ADD CONSTRAINT "@check:enum:kind" CHECK ("kind" IN ('CHECKOUT', 'RECURRING'));

ALTER TABLE "org.vernac.saas.payments.local"."LocalPayment" ADD CONSTRAINT "@check:enum:status" CHECK ("status" IN ('CANCELLED', 'EXPIRED', 'FAILED', 'OPEN', 'PAID', 'PENDING', 'REFUNDED', 'UNKNOWN'));

ALTER TABLE "org.vernac.saas.payments.local"."LocalPayment" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.local"."LocalPayment" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.local"."LocalRefund" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.local"."LocalRefund" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.local"."LocalPayment" ADD CONSTRAINT "@fk:reference:customerOperation" FOREIGN KEY ("customerOperation") REFERENCES "org.vernac.saas.payments.local"."LocalCustomer" ("id");

ALTER TABLE "org.vernac.saas.payments.local"."LocalRefund" ADD CONSTRAINT "@fk:reference:paymentOperation" FOREIGN KEY ("paymentOperation") REFERENCES "org.vernac.saas.payments.local"."LocalPayment" ("id");
