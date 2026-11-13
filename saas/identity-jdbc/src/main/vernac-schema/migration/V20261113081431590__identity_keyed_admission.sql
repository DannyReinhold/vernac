-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

CREATE TABLE "org.vernac.saas.identity.persistence"."IdentityAdmissionConfiguration" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "cursor" UUID,
    "id" UUID NOT NULL,
    "keyLabel" TEXT NOT NULL,
    "observed" BIGINT NOT NULL,
    "policyCheck" TEXT NOT NULL,
    CONSTRAINT "@pk:IdentityAdmissionConfiguration" PRIMARY KEY ("id")
);

CREATE TABLE "org.vernac.saas.identity.persistence"."IdentityAdmissionKey" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "digest" TEXT NOT NULL,
    "expires" BIGINT NOT NULL,
    "id" UUID NOT NULL,
    "kind" TEXT NOT NULL,
    "used" BIGINT NOT NULL,
    "window" BIGINT NOT NULL,
    CONSTRAINT "@pk:IdentityAdmissionKey" PRIMARY KEY ("id")
);

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityAdmissionConfiguration" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityAdmissionConfiguration" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityAdmissionKey" ADD CONSTRAINT "@check:enum:kind" CHECK ("kind" IN ('CHALLENGE_LOCATOR', 'CHALLENGE_SOURCE', 'LOGIN_KEY', 'LOGIN_SOURCE', 'MAIL_KEY', 'MAIL_SOURCE'));

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityAdmissionKey" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityAdmissionKey" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);
