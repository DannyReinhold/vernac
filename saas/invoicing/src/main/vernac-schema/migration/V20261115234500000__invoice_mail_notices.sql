-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

CREATE TABLE "org.vernac.saas.invoicing"."InvoiceNotice" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@nanoRemainder:due" SMALLINT NOT NULL,
    "@nanoRemainder:lease.until" SMALLINT,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "calls" INTEGER NOT NULL,
    "correlation" UUID NOT NULL,
    "due" TIMESTAMPTZ(6) NOT NULL,
    "id" UUID NOT NULL,
    "invoice" UUID NOT NULL,
    "lease.token" UUID,
    "lease.until" TIMESTAMPTZ(6),
    "reason" TEXT NOT NULL,
    "status" TEXT NOT NULL,
    CONSTRAINT "@pk:InvoiceNotice" PRIMARY KEY ("id")
);

ALTER TABLE "org.vernac.saas.invoicing"."InvoiceNotice" ADD CONSTRAINT "@check:absent:lease:org.vernac.saas.invoicing.InvoiceWorkLease" CHECK (("lease.token" IS NOT NULL) OR ("@nanoRemainder:lease.until" IS NULL AND "lease.token" IS NULL AND "lease.until" IS NULL));

ALTER TABLE "org.vernac.saas.invoicing"."InvoiceNotice" ADD CONSTRAINT "@check:enum:reason" CHECK ("reason" IN ('CONFIGURATION', 'NONE', 'OUTCOME_UNKNOWN', 'PROVIDER_REJECTED'));

ALTER TABLE "org.vernac.saas.invoicing"."InvoiceNotice" ADD CONSTRAINT "@check:enum:status" CHECK ("status" IN ('ACCEPTED', 'PENDING', 'REVIEW', 'SIMULATED'));

ALTER TABLE "org.vernac.saas.invoicing"."InvoiceNotice" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.invoicing"."InvoiceNotice" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.invoicing"."InvoiceNotice" ADD CONSTRAINT "@check:range:@nanoRemainder:due" CHECK ("@nanoRemainder:due" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.invoicing"."InvoiceNotice" ADD CONSTRAINT "@check:range:@nanoRemainder:lease.until" CHECK ("@nanoRemainder:lease.until" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.invoicing"."InvoiceNotice" ADD CONSTRAINT "@check:shape:lease.until" CHECK ((("lease.token" IS NOT NULL) AND ("lease.until" IS NOT NULL AND "@nanoRemainder:lease.until" IS NOT NULL)) OR ((NOT ("lease.token" IS NOT NULL)) AND ("lease.until" IS NULL AND "@nanoRemainder:lease.until" IS NULL)));

ALTER TABLE "org.vernac.saas.invoicing"."InvoiceNotice" ADD CONSTRAINT "@unique:InvoiceNotice:invoice" UNIQUE ("invoice");

ALTER TABLE "org.vernac.saas.invoicing"."InvoiceNotice" ADD CONSTRAINT "@fk:reference:invoice" FOREIGN KEY ("invoice") REFERENCES "org.vernac.saas.invoicing"."Invoice" ("id");
