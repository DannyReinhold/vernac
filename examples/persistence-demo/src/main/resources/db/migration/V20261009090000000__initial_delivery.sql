-- Generated migration candidate. Review before applying.
-- You are solely responsible for reviewing, testing, and approving this
-- migration before applying it to any database. Review operation ordering,
-- existing data, application compatibility, locking, execution time,
-- backups and recovery procedures. Generation and automated verification
-- do not guarantee correctness, safety, or suitability for your deployment.

CREATE SCHEMA IF NOT EXISTS "org.example.delivery";

CREATE TABLE "org.example.delivery"."Tour" (
    "@createdAt" TIMESTAMPTZ(6) NOT NULL,
    "@nanoRemainder:@createdAt" SMALLINT NOT NULL,
    "@nanoRemainder:@updatedAt" SMALLINT NOT NULL,
    "@present:notes:org.example.delivery.Notes" BOOLEAN NOT NULL,
    "@scale:price.amount" INTEGER NOT NULL,
    "@updatedAt" TIMESTAMPTZ(6) NOT NULL,
    "@version" BIGINT NOT NULL,
    "id" UUID NOT NULL,
    "notes.comment" TEXT,
    "notes.description" TEXT,
    "preferredStop" UUID,
    "price.amount" NUMERIC NOT NULL,
    "price.currency" TEXT NOT NULL,
    "status" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    CONSTRAINT "@pk:Tour" PRIMARY KEY ("id")
);

CREATE TABLE "org.example.delivery"."Tour.@entity:org.example.delivery.Parcel" (
    "@aggregateId" UUID NOT NULL,
    "@scale:weight" INTEGER NOT NULL,
    "id" UUID NOT NULL,
    "trackingNumber" TEXT NOT NULL,
    "weight" NUMERIC NOT NULL,
    CONSTRAINT "@pk:Tour.@entity:org.example.delivery.Parcel" PRIMARY KEY ("@aggregateId", "id")
);

CREATE TABLE "org.example.delivery"."Tour.@entity:org.example.delivery.Stop" (
    "@aggregateId" UUID NOT NULL,
    "address.city" TEXT NOT NULL,
    "address.street" TEXT NOT NULL,
    "id" UUID NOT NULL,
    CONSTRAINT "@pk:Tour.@entity:org.example.delivery.Stop" PRIMARY KEY ("@aggregateId", "id")
);

CREATE TABLE "org.example.delivery"."Tour.@entity:org.example.delivery.Stop.parcels" (
    "@aggregateId" UUID NOT NULL,
    "@entityId" UUID NOT NULL,
    "@ownerId" UUID NOT NULL,
    CONSTRAINT "@pk:Tour.@entity:org.example.delivery.Stop.parcels" PRIMARY KEY ("@aggregateId", "@ownerId", "@entityId")
);

CREATE TABLE "org.example.delivery"."Tour.allStops" (
    "@aggregateId" UUID NOT NULL,
    "@entityId" UUID NOT NULL,
    "@position" INTEGER NOT NULL,
    CONSTRAINT "@pk:Tour.allStops" PRIMARY KEY ("@aggregateId", "@position")
);

CREATE TABLE "org.example.delivery"."Tour.niceStops" (
    "@aggregateId" UUID NOT NULL,
    "@entityId" UUID NOT NULL,
    "@position" INTEGER NOT NULL,
    CONSTRAINT "@pk:Tour.niceStops" PRIMARY KEY ("@aggregateId", "@position")
);

CREATE TABLE "org.example.delivery"."Tour.tags" (
    "@ownerId" UUID NOT NULL,
    "@position" INTEGER NOT NULL,
    "@rowId" UUID NOT NULL,
    "value" TEXT NOT NULL,
    CONSTRAINT "@pk:Tour.tags" PRIMARY KEY ("@ownerId", "@position")
);

ALTER TABLE "org.example.delivery"."Tour" ADD CONSTRAINT "@check:absent:notes:org.example.delivery.Notes" CHECK (("@present:notes:org.example.delivery.Notes" IS TRUE) OR ("notes.comment" IS NULL AND "notes.description" IS NULL));

ALTER TABLE "org.example.delivery"."Tour" ADD CONSTRAINT "@check:enum:status" CHECK ("status" IN ('COMPLETED', 'PLANNED'));

ALTER TABLE "org.example.delivery"."Tour" ADD CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ("@nanoRemainder:@createdAt" BETWEEN 0 AND 999);

ALTER TABLE "org.example.delivery"."Tour" ADD CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ("@nanoRemainder:@updatedAt" BETWEEN 0 AND 999);

ALTER TABLE "org.example.delivery"."Tour" ADD CONSTRAINT "@check:range:price.amount" CHECK ("price.amount" NOT IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric));

ALTER TABLE "org.example.delivery"."Tour.@entity:org.example.delivery.Parcel" ADD CONSTRAINT "@check:range:weight" CHECK ("weight" NOT IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric));

ALTER TABLE "org.example.delivery"."Tour.allStops" ADD CONSTRAINT "@check:position" CHECK ("@position" >= 0);

ALTER TABLE "org.example.delivery"."Tour.niceStops" ADD CONSTRAINT "@check:position" CHECK ("@position" >= 0);

ALTER TABLE "org.example.delivery"."Tour.tags" ADD CONSTRAINT "@check:position" CHECK ("@position" >= 0);

ALTER TABLE "org.example.delivery"."Tour.tags" ADD CONSTRAINT "@unique:row:Tour.tags" UNIQUE ("@rowId");

ALTER TABLE "org.example.delivery"."Tour" ADD CONSTRAINT "@fk:entity:preferredStop" FOREIGN KEY ("id", "preferredStop") REFERENCES "org.example.delivery"."Tour.@entity:org.example.delivery.Stop" ("@aggregateId", "id") DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE "org.example.delivery"."Tour.@entity:org.example.delivery.Parcel" ADD CONSTRAINT "@fk:aggregate" FOREIGN KEY ("@aggregateId") REFERENCES "org.example.delivery"."Tour" ("id");

ALTER TABLE "org.example.delivery"."Tour.@entity:org.example.delivery.Stop" ADD CONSTRAINT "@fk:aggregate" FOREIGN KEY ("@aggregateId") REFERENCES "org.example.delivery"."Tour" ("id");

ALTER TABLE "org.example.delivery"."Tour.@entity:org.example.delivery.Stop.parcels" ADD CONSTRAINT "@fk:entity" FOREIGN KEY ("@aggregateId", "@entityId") REFERENCES "org.example.delivery"."Tour.@entity:org.example.delivery.Parcel" ("@aggregateId", "id") DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE "org.example.delivery"."Tour.@entity:org.example.delivery.Stop.parcels" ADD CONSTRAINT "@fk:owner" FOREIGN KEY ("@aggregateId", "@ownerId") REFERENCES "org.example.delivery"."Tour.@entity:org.example.delivery.Stop" ("@aggregateId", "id");

ALTER TABLE "org.example.delivery"."Tour.allStops" ADD CONSTRAINT "@fk:entity" FOREIGN KEY ("@aggregateId", "@entityId") REFERENCES "org.example.delivery"."Tour.@entity:org.example.delivery.Stop" ("@aggregateId", "id") DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE "org.example.delivery"."Tour.allStops" ADD CONSTRAINT "@fk:owner" FOREIGN KEY ("@aggregateId") REFERENCES "org.example.delivery"."Tour" ("id");

ALTER TABLE "org.example.delivery"."Tour.niceStops" ADD CONSTRAINT "@fk:entity" FOREIGN KEY ("@aggregateId", "@entityId") REFERENCES "org.example.delivery"."Tour.@entity:org.example.delivery.Stop" ("@aggregateId", "id") DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE "org.example.delivery"."Tour.niceStops" ADD CONSTRAINT "@fk:owner" FOREIGN KEY ("@aggregateId") REFERENCES "org.example.delivery"."Tour" ("id");

ALTER TABLE "org.example.delivery"."Tour.tags" ADD CONSTRAINT "@fk:owner" FOREIGN KEY ("@ownerId") REFERENCES "org.example.delivery"."Tour" ("id");
