-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

CREATE TABLE "org.vernac.saas.identity.persistence"."IdentityEncryptionKey" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "attempts" BIGINT NOT NULL,
    "fingerprint" TEXT COLLATE "pg_catalog"."C" NOT NULL,
    "id" UUID NOT NULL,
    "keyName" TEXT COLLATE "pg_catalog"."C" NOT NULL,
    CONSTRAINT "@pk:IdentityEncryptionKey" PRIMARY KEY ("id")
);

CREATE TABLE "org.vernac.saas.identity.persistence"."IdentityEncryptionRegistry" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "id" UUID NOT NULL,
    "used" BIGINT NOT NULL,
    CONSTRAINT "@pk:IdentityEncryptionRegistry" PRIMARY KEY ("id")
);

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityEncryptionKey" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityEncryptionKey" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityEncryptionKey" ADD CONSTRAINT "@unique:IdentityEncryptionKey:fingerprint" UNIQUE ("fingerprint");

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityEncryptionKey" ADD CONSTRAINT "@unique:IdentityEncryptionKey:keyName" UNIQUE ("keyName");

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityEncryptionRegistry" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."IdentityEncryptionRegistry" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);
