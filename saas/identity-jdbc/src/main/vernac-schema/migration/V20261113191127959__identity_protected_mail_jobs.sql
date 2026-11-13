-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

CREATE TABLE "org.vernac.saas.identity.persistence"."IdentityMailJob" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@nanoRemainder:due" SMALLINT NOT NULL,
    "@nanoRemainder:expiresAt" SMALLINT NOT NULL,
    "@nanoRemainder:leaseUntil" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "attempts" BIGINT NOT NULL,
    "challenge" UUID NOT NULL,
    "due" TIMESTAMPTZ(6) NOT NULL,
    "envelope" TEXT NOT NULL,
    "expiresAt" TIMESTAMPTZ(6) NOT NULL,
    "id" UUID NOT NULL,
    "leaseToken" UUID NOT NULL,
    "leaseUntil" TIMESTAMPTZ(6) NOT NULL,
    "purpose" TEXT NOT NULL,
    "state" TEXT NOT NULL,
    "subject" UUID NOT NULL,
    CONSTRAINT "@pk:IdentityMailJob" PRIMARY KEY ("id")
);

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityMailJob" ADD CONSTRAINT "@check:enum:purpose" CHECK ("purpose" IN ('RECOVERY', 'VERIFICATION'));

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityMailJob" ADD CONSTRAINT "@check:enum:state" CHECK ("state" IN ('CANCELLED', 'LEASED', 'READY'));

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityMailJob" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityMailJob" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityMailJob" ADD CONSTRAINT "@check:range:@nanoRemainder:due" CHECK ("@nanoRemainder:due" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityMailJob" ADD CONSTRAINT "@check:range:@nanoRemainder:expiresAt" CHECK ("@nanoRemainder:expiresAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityMailJob" ADD CONSTRAINT "@check:range:@nanoRemainder:leaseUntil" CHECK ("@nanoRemainder:leaseUntil" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityMailJob" ADD CONSTRAINT "@unique:IdentityMailJob:challenge" UNIQUE ("challenge");
