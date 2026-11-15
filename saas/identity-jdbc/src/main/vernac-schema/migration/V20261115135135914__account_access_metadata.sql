-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

CREATE TABLE "org.vernac.saas.identity.persistence"."Account" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "id" UUID NOT NULL,
    "kind" TEXT NOT NULL,
    "status" TEXT NOT NULL,
    "tenant" UUID NOT NULL,
    CONSTRAINT "@pk:Account" PRIMARY KEY ("id")
);

CREATE TABLE "org.vernac.saas.identity.persistence"."AccountMembership" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "account" UUID NOT NULL,
    "id" UUID NOT NULL,
    "memberKey" TEXT COLLATE "pg_catalog"."C" NOT NULL,
    "owner" BOOLEAN NOT NULL,
    "roles" TEXT NOT NULL,
    "status" TEXT NOT NULL,
    "subject" UUID NOT NULL,
    CONSTRAINT "@pk:AccountMembership" PRIMARY KEY ("id")
);

CREATE TABLE "org.vernac.saas.identity.persistence"."PersonalAccount" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "account" UUID NOT NULL,
    "id" UUID NOT NULL,
    "subject" UUID NOT NULL,
    CONSTRAINT "@pk:PersonalAccount" PRIMARY KEY ("id")
);

ALTER TABLE "org.vernac.saas.identity.persistence"."Account" ADD CONSTRAINT "@check:enum:kind" CHECK ("kind" IN ('ORGANIZATION', 'PERSONAL'));

ALTER TABLE "org.vernac.saas.identity.persistence"."Account" ADD CONSTRAINT "@check:enum:status" CHECK ("status" IN ('ACTIVE', 'CLOSED'));

ALTER TABLE "org.vernac.saas.identity.persistence"."Account" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."Account" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."Account" ADD CONSTRAINT "@unique:Account:tenant" UNIQUE ("tenant");

ALTER TABLE "org.vernac.saas.identity.persistence"."AccountMembership" ADD CONSTRAINT "@check:enum:status" CHECK ("status" IN ('ACTIVE', 'REMOVED'));

ALTER TABLE "org.vernac.saas.identity.persistence"."AccountMembership" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."AccountMembership" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."AccountMembership" ADD CONSTRAINT "@unique:AccountMembership:memberKey" UNIQUE ("memberKey");

ALTER TABLE "org.vernac.saas.identity.persistence"."PersonalAccount" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."PersonalAccount" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.vernac.saas.identity.persistence"."PersonalAccount" ADD CONSTRAINT "@unique:PersonalAccount:account" UNIQUE ("account");

ALTER TABLE "org.vernac.saas.identity.persistence"."AccountMembership" ADD CONSTRAINT "@fk:reference:account" FOREIGN KEY ("account") REFERENCES "org.vernac.saas.identity.persistence"."Account" ("id");

ALTER TABLE "org.vernac.saas.identity.persistence"."AccountMembership" ADD CONSTRAINT "@fk:reference:subject" FOREIGN KEY ("subject") REFERENCES "org.vernac.saas.identity.persistence"."Identity" ("id");

ALTER TABLE "org.vernac.saas.identity.persistence"."PersonalAccount" ADD CONSTRAINT "@fk:reference:account" FOREIGN KEY ("account") REFERENCES "org.vernac.saas.identity.persistence"."Account" ("id");

ALTER TABLE "org.vernac.saas.identity.persistence"."PersonalAccount" ADD CONSTRAINT "@fk:reference:subject" FOREIGN KEY ("subject") REFERENCES "org.vernac.saas.identity.persistence"."Identity" ("id");
