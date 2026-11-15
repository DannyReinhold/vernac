-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

CREATE SCHEMA IF NOT EXISTS "org.vernac.saas.payments.persistence";

CREATE TABLE "org.vernac.saas.payments.persistence"."BillingAgreement" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@nanoRemainder:nextBillingAt" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "account" UUID NOT NULL,
    "customer" TEXT NOT NULL,
    "id" UUID NOT NULL,
    "mandate" TEXT NOT NULL,
    "nextBillingAt" TIMESTAMPTZ(6) NOT NULL,
    "profile" TEXT NOT NULL,
    CONSTRAINT "@pk:BillingAgreement" PRIMARY KEY ("id")
);

CREATE TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@nanoRemainder:due" SMALLINT NOT NULL,
    "@nanoRemainder:financialStarted" SMALLINT,
    "@nanoRemainder:lease.until" SMALLINT,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "account" UUID NOT NULL,
    "calls" INTEGER NOT NULL,
    "checkout" TEXT,
    "customer" TEXT,
    "customerOperation" UUID NOT NULL,
    "due" TIMESTAMPTZ(6) NOT NULL,
    "financialStarted" TIMESTAMPTZ(6),
    "id" UUID NOT NULL,
    "key" TEXT COLLATE "pg_catalog"."C" NOT NULL,
    "kind" TEXT NOT NULL,
    "lease.token" UUID,
    "lease.until" TIMESTAMPTZ(6),
    "mandate" TEXT,
    "operation" UUID NOT NULL,
    "order" UUID NOT NULL,
    "profile" TEXT NOT NULL,
    "reason" TEXT NOT NULL,
    "refunded" BIGINT NOT NULL,
    "remote" TEXT,
    "stage" TEXT NOT NULL,
    "state" TEXT NOT NULL,
    CONSTRAINT "@pk:PaymentAttempt" PRIMARY KEY ("id")
);

CREATE TABLE "org.vernac.saas.payments.persistence"."PaymentOrder" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@nanoRemainder:period.end" SMALLINT NOT NULL,
    "@nanoRemainder:period.start" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "account" UUID NOT NULL,
    "attempts" INTEGER NOT NULL,
    "cycle" TEXT NOT NULL,
    "id" UUID NOT NULL,
    "paidAttempt" UUID,
    "period.end" TIMESTAMPTZ(6) NOT NULL,
    "period.start" TIMESTAMPTZ(6) NOT NULL,
    "reason" TEXT NOT NULL,
    "request" TEXT COLLATE "pg_catalog"."C" NOT NULL,
    "status" TEXT NOT NULL,
    "subscription" UUID NOT NULL,
    "terms.benefits" TEXT NOT NULL,
    "terms.currency" TEXT NOT NULL,
    "terms.interval" TEXT NOT NULL,
    "terms.plan" TEXT NOT NULL,
    "terms.price" BIGINT NOT NULL,
    "terms.revision" TEXT NOT NULL,
    CONSTRAINT "@pk:PaymentOrder" PRIMARY KEY ("id")
);

CREATE TABLE "org.vernac.saas.payments.persistence"."PaymentRefund" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@nanoRemainder:due" SMALLINT NOT NULL,
    "@nanoRemainder:financialStarted" SMALLINT,
    "@nanoRemainder:lease.until" SMALLINT,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "account" UUID NOT NULL,
    "amount" BIGINT NOT NULL,
    "attempt" UUID NOT NULL,
    "calls" INTEGER NOT NULL,
    "due" TIMESTAMPTZ(6) NOT NULL,
    "financialStarted" TIMESTAMPTZ(6),
    "id" UUID NOT NULL,
    "lease.token" UUID,
    "lease.until" TIMESTAMPTZ(6),
    "operation" UUID NOT NULL,
    "reason" TEXT NOT NULL,
    "remote" TEXT,
    "state" TEXT NOT NULL,
    CONSTRAINT "@pk:PaymentRefund" PRIMARY KEY ("id")
);

CREATE TABLE "org.vernac.saas.payments.persistence"."ProviderRegistration" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "environment" TEXT NOT NULL,
    "id" UUID NOT NULL,
    "key" TEXT COLLATE "pg_catalog"."C" NOT NULL,
    "kind" TEXT NOT NULL,
    CONSTRAINT "@pk:ProviderRegistration" PRIMARY KEY ("id")
);

CREATE TABLE "org.vernac.saas.payments.persistence"."RegisteredPaymentResource" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "attempt" UUID NOT NULL,
    "id" UUID NOT NULL,
    "profile" TEXT NOT NULL,
    "remote" TEXT NOT NULL,
    "route" TEXT COLLATE "pg_catalog"."C" NOT NULL,
    CONSTRAINT "@pk:RegisteredPaymentResource" PRIMARY KEY ("id")
);

ALTER TABLE "org.vernac.saas.payments.persistence"."BillingAgreement" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.persistence"."BillingAgreement" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.persistence"."BillingAgreement" ADD CONSTRAINT "@check:range:@nanoRemainder:nextBillingAt" CHECK ("@nanoRemainder:nextBillingAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" ADD CONSTRAINT "@check:absent:financialStarted~efd8e794335cc0092cbe3fb34438d606" CHECK (("financialStarted" IS NOT NULL) OR ("@nanoRemainder:financialStarted" IS NULL AND "financialStarted" IS NULL));

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" ADD CONSTRAINT "@check:absent:lease:org.vernac~a0a3e9c31e4ff6509962488cac58dd2a" CHECK (("lease.token" IS NOT NULL) OR ("@nanoRemainder:lease.until" IS NULL AND "lease.token" IS NULL AND "lease.until" IS NULL));

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" ADD CONSTRAINT "@check:enum:kind" CHECK ("kind" IN ('FIRST', 'RECURRING'));

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" ADD CONSTRAINT "@check:enum:reason" CHECK ("reason" IN ('DUPLICATE_PAID', 'INVALID_PROVIDER_FACTS', 'MANDATE_UNAVAILABLE', 'NONE', 'OUTCOME_UNKNOWN', 'PROVIDER_UNAVAILABLE', 'REFUND_UNCERTAIN', 'SETTLEMENT_CONFLICT'));

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" ADD CONSTRAINT "@check:enum:stage" CHECK ("stage" IN ('APPLY', 'CUSTOMER_READY', 'DONE', 'NEW', 'PREPARING', 'REVIEW', 'WAITING'));

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" ADD CONSTRAINT "@check:enum:state" CHECK ("state" IN ('CANCELLED', 'EXPIRED', 'FAILED', 'OPEN', 'PAID', 'PENDING', 'REFUNDED', 'UNKNOWN'));

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" ADD CONSTRAINT "@check:range:@nanoRemainder:due" CHECK ("@nanoRemainder:due" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" ADD CONSTRAINT "@check:range:@nanoRemainder:financialStarted" CHECK ("@nanoRemainder:financialStarted" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" ADD CONSTRAINT "@check:range:@nanoRemainder:lease.until" CHECK ("@nanoRemainder:lease.until" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" ADD CONSTRAINT "@check:shape:financialStarted" CHECK ((("financialStarted" IS NOT NULL) AND ("financialStarted" IS NOT NULL AND "@nanoRemainder:financialStarted" IS NOT NULL)) OR ((NOT ("financialStarted" IS NOT NULL)) AND ("financialStarted" IS NULL AND "@nanoRemainder:financialStarted" IS NULL)));

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" ADD CONSTRAINT "@check:shape:lease.until" CHECK ((("lease.token" IS NOT NULL) AND ("lease.until" IS NOT NULL AND "@nanoRemainder:lease.until" IS NOT NULL)) OR ((NOT ("lease.token" IS NOT NULL)) AND ("lease.until" IS NULL AND "@nanoRemainder:lease.until" IS NULL)));

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" ADD CONSTRAINT "@unique:PaymentAttempt:key" UNIQUE ("key");

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" ADD CONSTRAINT "@unique:PaymentAttempt:operation" UNIQUE ("operation");

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentOrder" ADD CONSTRAINT "@check:enum:reason" CHECK ("reason" IN ('DUPLICATE_PAID', 'INVALID_PROVIDER_FACTS', 'MANDATE_UNAVAILABLE', 'NONE', 'OUTCOME_UNKNOWN', 'PROVIDER_UNAVAILABLE', 'REFUND_UNCERTAIN', 'SETTLEMENT_CONFLICT'));

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentOrder" ADD CONSTRAINT "@check:enum:status" CHECK ("status" IN ('APPLIED', 'CLOSED', 'OPEN', 'PAID', 'REVIEW'));

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentOrder" ADD CONSTRAINT "@check:enum:terms.currency" CHECK ("terms.currency" IN ('EUR'));

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentOrder" ADD CONSTRAINT "@check:enum:terms.interval" CHECK ("terms.interval" IN ('MONTHLY', 'YEARLY'));

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentOrder" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentOrder" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentOrder" ADD CONSTRAINT "@check:range:@nanoRemainder:period.end" CHECK ("@nanoRemainder:period.end" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentOrder" ADD CONSTRAINT "@check:range:@nanoRemainder:period.start" CHECK ("@nanoRemainder:period.start" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentOrder" ADD CONSTRAINT "@unique:PaymentOrder:request" UNIQUE ("request");

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentRefund" ADD CONSTRAINT "@check:absent:financialStarted~efd8e794335cc0092cbe3fb34438d606" CHECK (("financialStarted" IS NOT NULL) OR ("@nanoRemainder:financialStarted" IS NULL AND "financialStarted" IS NULL));

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentRefund" ADD CONSTRAINT "@check:absent:lease:org.vernac~a0a3e9c31e4ff6509962488cac58dd2a" CHECK (("lease.token" IS NOT NULL) OR ("@nanoRemainder:lease.until" IS NULL AND "lease.token" IS NULL AND "lease.until" IS NULL));

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentRefund" ADD CONSTRAINT "@check:enum:reason" CHECK ("reason" IN ('DUPLICATE_PAID', 'INVALID_PROVIDER_FACTS', 'MANDATE_UNAVAILABLE', 'NONE', 'OUTCOME_UNKNOWN', 'PROVIDER_UNAVAILABLE', 'REFUND_UNCERTAIN', 'SETTLEMENT_CONFLICT'));

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentRefund" ADD CONSTRAINT "@check:enum:state" CHECK ("state" IN ('FAILED', 'PENDING', 'REFUNDED', 'UNKNOWN'));

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentRefund" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentRefund" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentRefund" ADD CONSTRAINT "@check:range:@nanoRemainder:due" CHECK ("@nanoRemainder:due" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentRefund" ADD CONSTRAINT "@check:range:@nanoRemainder:financialStarted" CHECK ("@nanoRemainder:financialStarted" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentRefund" ADD CONSTRAINT "@check:range:@nanoRemainder:lease.until" CHECK ("@nanoRemainder:lease.until" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentRefund" ADD CONSTRAINT "@check:shape:financialStarted" CHECK ((("financialStarted" IS NOT NULL) AND ("financialStarted" IS NOT NULL AND "@nanoRemainder:financialStarted" IS NOT NULL)) OR ((NOT ("financialStarted" IS NOT NULL)) AND ("financialStarted" IS NULL AND "@nanoRemainder:financialStarted" IS NULL)));

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentRefund" ADD CONSTRAINT "@check:shape:lease.until" CHECK ((("lease.token" IS NOT NULL) AND ("lease.until" IS NOT NULL AND "@nanoRemainder:lease.until" IS NOT NULL)) OR ((NOT ("lease.token" IS NOT NULL)) AND ("lease.until" IS NULL AND "@nanoRemainder:lease.until" IS NULL)));

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentRefund" ADD CONSTRAINT "@unique:PaymentRefund:operation" UNIQUE ("operation");

ALTER TABLE "org.vernac.saas.payments.persistence"."ProviderRegistration" ADD CONSTRAINT "@check:enum:environment" CHECK ("environment" IN ('PRODUCTION', 'SIMULATED', 'TEST'));

ALTER TABLE "org.vernac.saas.payments.persistence"."ProviderRegistration" ADD CONSTRAINT "@check:enum:kind" CHECK ("kind" IN ('LOCAL', 'MOLLIE', 'STRIPE'));

ALTER TABLE "org.vernac.saas.payments.persistence"."ProviderRegistration" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.persistence"."ProviderRegistration" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.persistence"."ProviderRegistration" ADD CONSTRAINT "@unique:ProviderRegistration:key" UNIQUE ("key");

ALTER TABLE "org.vernac.saas.payments.persistence"."RegisteredPaymentResource" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.persistence"."RegisteredPaymentResource" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.payments.persistence"."RegisteredPaymentResource" ADD CONSTRAINT "@unique:RegisteredPaymentResource:route" UNIQUE ("route");

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" ADD CONSTRAINT "@fk:reference:order" FOREIGN KEY ("order") REFERENCES "org.vernac.saas.payments.persistence"."PaymentOrder" ("id");

ALTER TABLE "org.vernac.saas.payments.persistence"."PaymentRefund" ADD CONSTRAINT "@fk:reference:attempt" FOREIGN KEY ("attempt") REFERENCES "org.vernac.saas.payments.persistence"."PaymentAttempt" ("id");

ALTER TABLE "org.vernac.saas.payments.persistence"."RegisteredPaymentResource" ADD CONSTRAINT "@fk:reference:attempt" FOREIGN KEY ("attempt") REFERENCES "org.vernac.saas.payments.persistence"."PaymentAttempt" ("id");
