-- V1: initial schema for water pressure monitor

CREATE TABLE device (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    api_key     VARCHAR(128) NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE measurement (
    id           BIGSERIAL PRIMARY KEY,
    device_id    BIGINT       NOT NULL REFERENCES device(id),
    pressure_psi NUMERIC(7,2) NOT NULL,
    measured_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Main driver of chart query performance
CREATE INDEX idx_measurement_device_time ON measurement (device_id, measured_at);
-- Append-only time-series: BRIN gives near-partition pruning at tiny size
CREATE INDEX idx_measurement_time_brin ON measurement USING BRIN (measured_at);

-- Compaction target: raw rows older than the retention window are rolled up here
CREATE TABLE measurement_hourly (
    id               BIGSERIAL PRIMARY KEY,
    device_id        BIGINT       NOT NULL REFERENCES device(id),
    hour_start       TIMESTAMPTZ  NOT NULL,
    avg_pressure_psi NUMERIC(7,2) NOT NULL,
    min_pressure_psi NUMERIC(7,2) NOT NULL,
    max_pressure_psi NUMERIC(7,2) NOT NULL,
    sample_count     INTEGER      NOT NULL,
    CONSTRAINT uq_hourly_device_hour UNIQUE (device_id, hour_start)
);

CREATE INDEX idx_hourly_device_time ON measurement_hourly (device_id, hour_start);

-- Global settings (single row for now; per-device settings are a future migration)
CREATE TABLE alert_settings (
    id                      BIGSERIAL PRIMARY KEY,
    low_threshold_psi       NUMERIC(7,2) NOT NULL DEFAULT 20,
    drop_delta_psi          NUMERIC(7,2) NOT NULL DEFAULT 15,
    drop_window_minutes     INTEGER      NOT NULL DEFAULT 10,
    offline_timeout_minutes INTEGER      NOT NULL DEFAULT 10,
    cooldown_minutes        INTEGER      NOT NULL DEFAULT 30,
    telegram_enabled        BOOLEAN      NOT NULL DEFAULT false,
    telegram_bot_token      VARCHAR(200),
    telegram_chat_id        VARCHAR(100),
    display_unit            VARCHAR(10)  NOT NULL DEFAULT 'PSI',
    compaction_enabled      BOOLEAN      NOT NULL DEFAULT true,
    compaction_days         INTEGER      NOT NULL DEFAULT 90
);

CREATE TABLE alert_event (
    id          BIGSERIAL PRIMARY KEY,
    device_id   BIGINT      NOT NULL REFERENCES device(id),
    type        VARCHAR(30) NOT NULL, -- LOW_PRESSURE | SUDDEN_DROP | OFFLINE
    message     TEXT        NOT NULL,
    fired_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);

CREATE INDEX idx_alert_event_device_type ON alert_event (device_id, type, fired_at DESC);
