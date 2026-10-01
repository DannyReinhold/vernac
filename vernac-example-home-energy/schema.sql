CREATE TABLE IF NOT EXISTS energy_storage (
    id VARCHAR(255) PRIMARY KEY,
    capacity TEXT NOT NULL,
    battery_soc TEXT NOT NULL,
    watt_hours TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);