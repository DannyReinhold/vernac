CREATE TABLE IF NOT EXISTS energy_storage (
     id UUID PRIMARY KEY,
     capacity INTEGER NOT NULL,
     battery_soc INTEGER NOT NULL,
     watt_hours INTEGER NOT NULL,
     created_at TIMESTAMPTZ NOT NULL,
     updated_at TIMESTAMPTZ NOT NULL,
     version BIGINT NOT NULL DEFAULT 0
 );