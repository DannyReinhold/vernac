-- SQL representation test: no generated writer/loader is exercised here.
-- Fixed UUIDs belong exclusively to this disposable test database.
INSERT INTO "model"."Root" (id, "requiredStop", "@version", "@createdAt", "@updatedAt",
    "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt")
VALUES ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000011', 0, now(), now(), 0, 0),
       ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000012', 0, now(), now(), 0, 0);
INSERT INTO "model"."Root.@entity:model.Stop" ("@aggregateId",id,label) VALUES
('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000011','Shared'),
('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000012','Other root'),
('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000011','Same ID in another root');
INSERT INTO "model"."Root.@entity:model.Parcel" ("@aggregateId",id,label) VALUES
('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000021','Parcel');
INSERT INTO "model"."Root.allStops" ("@aggregateId","@position","@entityId") VALUES
('00000000-0000-0000-0000-000000000001',0,'00000000-0000-0000-0000-000000000011'),
('00000000-0000-0000-0000-000000000001',1,'00000000-0000-0000-0000-000000000011');
INSERT INTO "model"."Root.niceStops" ("@aggregateId","@position","@entityId") VALUES
('00000000-0000-0000-0000-000000000001',0,'00000000-0000-0000-0000-000000000011');
INSERT INTO "model"."Root.@entity:model.Stop.parcels" ("@aggregateId","@ownerId","@entityId") VALUES
('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000011','00000000-0000-0000-0000-000000000021');
INSERT INTO "model"."Root.@entity:model.Stop.tags" ("@aggregateId","@ownerId","@position","@rowId",value) VALUES
('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000011',0,'00000000-0000-0000-0000-000000000031','Tag'),
('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000011',0,'00000000-0000-0000-0000-000000000032','Another tag');
SET CONSTRAINTS ALL IMMEDIATE;
DO $$
BEGIN
    IF (SELECT count(*) FROM "model"."Root.allStops") <> 2 THEN
        RAISE EXCEPTION 'Repeated list reference was lost';
    END IF;
    BEGIN
        INSERT INTO "model"."Root.niceStops" ("@aggregateId","@entityId","@position") VALUES
        ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000012',2);
        RAISE EXCEPTION 'Cross-aggregate target was accepted';
    EXCEPTION WHEN foreign_key_violation THEN NULL;
    END;
    BEGIN
        INSERT INTO "model"."Root.@entity:model.Stop.parcels" ("@aggregateId","@ownerId","@entityId") VALUES
        ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000011','00000000-0000-0000-0000-000000000021');
        RAISE EXCEPTION 'Duplicate set row was accepted';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;
    DELETE FROM "model"."Root.niceStops";
    IF NOT EXISTS (SELECT FROM "model"."Root.@entity:model.Stop" WHERE label='Shared') THEN
        RAISE EXCEPTION 'Removing a relationship deleted shared state';
    END IF;
    BEGIN
        DELETE FROM "model"."Root.@entity:model.Stop" WHERE label='Shared';
        RAISE EXCEPTION 'Referenced state could be removed';
    EXCEPTION WHEN foreign_key_violation THEN NULL;
    END;
END $$;
