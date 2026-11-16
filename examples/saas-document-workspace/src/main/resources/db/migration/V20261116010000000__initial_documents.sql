-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

CREATE SCHEMA IF NOT EXISTS "example.documentworkspace";

CREATE TABLE "example.documentworkspace"."Document" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@tenantId" UUID NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "id" UUID NOT NULL,
    "text" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    CONSTRAINT "@pk:Document" PRIMARY KEY ("id")
);

ALTER TABLE "example.documentworkspace"."Document" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "example.documentworkspace"."Document" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "example.documentworkspace"."Document" ADD CONSTRAINT "@unique:tenant:Document" UNIQUE ("@tenantId", "id");

ALTER TABLE "example.documentworkspace"."Document" ENABLE ROW LEVEL SECURITY;

ALTER TABLE "example.documentworkspace"."Document" FORCE ROW LEVEL SECURITY;

CREATE POLICY "@tenant" ON "example.documentworkspace"."Document" USING ("@tenantId" = NULLIF(current_setting('vernac.tenant_id', true), '')::uuid) WITH CHECK ("@tenantId" = NULLIF(current_setting('vernac.tenant_id', true), '')::uuid);
