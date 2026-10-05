DROP TABLE IF EXISTS energy_storage;

CREATE TABLE IF NOT EXISTS energy_storage (
            id UUID PRIMARY KEY,
            capacity INTEGER NOT NULL,
            soc INTEGER NOT NULL,
            stored_energy INTEGER NOT NULL,
            created_at TIMESTAMPTZ NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            version BIGINT NOT NULL DEFAULT 0
);
