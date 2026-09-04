-- V2: multi-metric device support + tank water level

ALTER TABLE device
    ADD COLUMN device_type VARCHAR(20) NOT NULL DEFAULT 'PRESSURE';

CREATE TABLE tank_level (
    id            BIGSERIAL PRIMARY KEY,
    device_id     BIGINT        NOT NULL REFERENCES device (id),
    level_percent NUMERIC(5, 2) NOT NULL,
    distance_cm   NUMERIC(7, 2),
    measured_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_tank_level_device_time ON tank_level (device_id, measured_at);
CREATE INDEX idx_tank_level_time_brin ON tank_level USING BRIN (measured_at);