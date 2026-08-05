# Water Pressure Monitor

ESP32 + Spring Boot + PostgreSQL + Telegram. Monitors home water pressure, alerts on
pressure drops (burst pipe / leak), low pressure, and sensor offline. Dashboard with
day / month / year history charts. Deploys to Railway with `dev` and `master` →
development / production environments.

📄 **[PROJECT.md](PROJECT.md)** — full context, architecture, and decisions log.
🚀 **[DEPLOYMENT.md](DEPLOYMENT.md)** — step-by-step Railway / Telegram / ESP32 setup.

## Layout

```
├── src/main/java/com/watermonitor/
│   ├── auth/          dashboard login (single password, session cookie)
│   ├── config/        env properties, security filter (API key + session), bootstrap seeding
│   ├── device/        device registry (multi-device ready, single device today)
│   ├── measurement/   ingestion, day/month/year aggregation (raw + compacted)
│   ├── alert/         alert engine, settings, Telegram client, offline watchdog
│   └── compaction/    daily retention job (raw > 90 days → hourly rollups)
├── src/main/resources/
│   ├── db/migration/  Flyway schema (composite + BRIN indexes — no partitioning by design)
│   └── static/        dashboard SPA (Chart.js)
├── arduino/pressure_sensor/   ESP32 firmware (read the wiring warning!)
├── .github/workflows/ci.yml   build + tests gate for dev/master
├── Dockerfile, railway.toml
└── DEPLOYMENT.md, PROJECT.md
```

## Quick start (local)

```bash
docker run -d --name wm-pg -e POSTGRES_DB=watermonitor -e POSTGRES_USER=watermonitor \
  -e POSTGRES_PASSWORD=watermonitor -p 5432:5432 postgres:16-alpine
mvn spring-boot:run
# http://localhost:8080 — password: changeme
```

Send a fake measurement:

```bash
curl -X POST http://localhost:8080/api/measurements \
  -H "Content-Type: application/json" -H "X-Api-Key: dev-key-change-me" \
  -d '{"pressurePsi": 55.3}'
```

Run tests (Docker required for Testcontainers): `mvn verify`

## API

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /api/measurements` | `X-Api-Key` | ESP32 ingestion |
| `GET /api/measurements?granularity=day\|month\|year&from&to` | session | chart buckets (avg/min/max) |
| `GET /api/status` | session | latest reading + online state |
| `GET/PUT /api/settings` | session | thresholds, Telegram, units, compaction |
| `GET /api/alerts` | session | last 20 alert events |
| `GET /api/health` | none | Railway health check |
