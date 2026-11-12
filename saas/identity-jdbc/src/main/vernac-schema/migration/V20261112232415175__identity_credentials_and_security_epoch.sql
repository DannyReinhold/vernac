-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

ALTER TABLE "org.vernac.saas.identity.persistence"."Identity" ADD COLUMN "securityEpoch" BIGINT;

UPDATE "org.vernac.saas.identity.persistence"."Identity" SET "securityEpoch" = (0) WHERE "securityEpoch" IS NULL;

ALTER TABLE "org.vernac.saas.identity.persistence"."Identity" ALTER COLUMN "securityEpoch" SET NOT NULL;

CREATE TABLE "org.vernac.saas.identity.persistence"."IdentityCredential" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "encoding" TEXT NOT NULL,
    "id" UUID NOT NULL,
    CONSTRAINT "@pk:IdentityCredential" PRIMARY KEY ("id")
);

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityCredential" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityCredential" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);
