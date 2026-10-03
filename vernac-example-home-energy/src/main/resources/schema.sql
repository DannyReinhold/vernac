DROP TABLE IF EXISTS energy_storage;

CREATE TABLE energy_storage (
                                id VARCHAR(64) PRIMARY KEY,
                                capacity INT NOT NULL,
                                soc INT NOT NULL,
                                stored_energy INT NOT NULL,
                                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                                updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                                version BIGINT NOT NULL
);