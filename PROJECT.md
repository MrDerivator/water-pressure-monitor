# Water Pressure Monitor — Project Context

> **Purpose of this file:** Living context document. Read this first to get up to speed
> on the project state, decisions made, and what remains. Update the *Status & Next Steps*
> section as development progresses.

**Last updated:** 2026-06-12 (rev 3 — implementation complete, pending first CI run & deploy)
**Status:** Code complete (backend, dashboard, firmware, CI, deploy config). Next: push to GitHub, let CI verify, deploy per DEPLOYMENT.md.

---

## 1. What we're building

An IoT water-pressure monitoring system:

- An **ESP32 (Arduino)** reads a water pressure sensor and pushes measurements over WiFi
  to a cloud app every ~2 minutes.
- A **Spring Boot application hosted on Railway** ingests measurements, stores them in
  **PostgreSQL**, evaluates alert rules, and sends **Telegram alerts** on pressure drops
  (burst pipe / leak detection), low pressure, or device going offline.
- A **web dashboard** (served by the same Spring Boot app) shows current pressure and
  historical charts with day / month / year granularity, and lets the owner configure
  alert thresholds and Telegram settings.

Primary user: single homeowner. One sensor now, **designed for multiple devices later**.

---

## 2. Hardware

| Item | Detail |
|---|---|
| MCU | ESP32 (Arduino framework) |
| Sensor | **1.2 MPa (174 PSI)** analog pressure transducer, DC 5V supply, **G1/4 thread (BSPP)**, **0.5–4.5 V output** (0.5 V = 0 PSI, 4.5 V = 174 PSI → ≈43.5 PSI per volt). *(Corrected from earlier 100 PSI / 1/8" NPT model.)* |
| ⚠️ Critical | ESP32 ADC max is ~3.3 V → sensor output **must go through a voltage divider** (e.g. 10 kΩ / 20 kΩ → 4.5 V scales to ~3.0 V). Direct connection damages the pin and clips readings above ~70 PSI. |
| Sampling | Average ~20 ADC samples per reading (median/mean filter — ESP32 ADC is noisy). Calibration: `PSI = (V_sensor − 0.5) × 174 / 4.0` after un-scaling the divider. |
| Send interval | Every **2 minutes** (configurable constant in sketch). |
| Expected range | Household water: typically 40–80 PSI → lower third of the 174 PSI range. Coarser resolution per PSI than a 100 PSI sensor, but with averaging still ample for leak detection. |

ESP32 firmware requirements:
- WiFi connect with retry/backoff.
- HTTPS `POST /api/measurements` with JSON body; identifies itself with **device ID + API key header**.
- Optional small offline buffer (retry queue) when WiFi/API unavailable.

---

## 3. Architecture

```
[Pressure sensor 1.2MPa/174PSI] --analog 0.5-4.5V--> [voltage divider] --> [ESP32]
[ESP32] --WiFi/HTTPS POST--> [Spring Boot app on Railway] <--> [Railway PostgreSQL]
[Spring Boot] --Bot API--> [Telegram] --> owner's phone
[Browser dashboard] --HTTPS--> [Spring Boot app (serves static SPA + REST API)]
[GitHub repo] --Actions CI--> [Railway auto-deploy: dev & production environments]
```

**Single Railway service** runs both REST API and dashboard (static files served by
Spring Boot) → one deployment, one URL, no CORS. Postgres via Railway plugin, internal
networking only (not publicly exposed).

### Tech stack
- **Backend:** Java 21, Spring Boot 3.x, Maven, Flyway migrations, Spring Data JPA.
- **DB:** PostgreSQL (Railway managed), one instance per environment.
- **Frontend:** Plain HTML/JS + Chart.js (no framework), served from Spring Boot static resources.
- **Alerts:** Telegram Bot API, outbound only (bot via @BotFather; token + chat ID in settings).
- **Tests:** JUnit unit tests (alert engine especially) + Testcontainers Postgres integration tests.
- **Deploy:** Docker (Dockerfile) + `railway.toml`; Railway environments mapped to git branches.

---

## 4. REST API

| Method/Path | Purpose | Auth |
|---|---|---|
| `POST /api/measurements` | Ingestion from ESP32. JSON: `{deviceId, pressure, timestamp?}` | `X-Api-Key` header (per-device key) |
| `GET /api/measurements?granularity=hour\|day\|month&from=&to=&deviceId=` | Aggregated buckets (avg/min/max via SQL `date_trunc`) for charts | Dashboard session |
| `GET /api/status` | Latest reading, last-seen time, online/offline state | Dashboard session |
| `GET /api/settings` / `PUT /api/settings` | Alert config: thresholds, drop rule, offline timeout, Telegram token/chat, units | Dashboard session |

Dashboard auth: **simple single-password login** (session cookie). Ingestion auth: API key.

---

## 5. Data model (planned)

- `device` — exists from day one (multi-device ready): id, name, api_key, created_at.
- `measurement` — id, device_id FK, pressure_psi (numeric), measured_at (timestamptz).
- `measurement_hourly` — compaction target: device_id, hour_start (timestamptz),
  avg/min/max pressure_psi, sample_count. Chart queries union raw + compacted transparently.
- `alert_settings` — per device (or global row for now): low_threshold_psi, drop_delta_psi,
  drop_window_minutes, offline_timeout_minutes, cooldown_minutes, telegram_bot_token,
  telegram_chat_id, telegram_enabled, display_unit (PSI/bar/kPa).
- `alert_event` — log of fired alerts: type, device_id, fired_at, resolved_at, message.

### Indexing & performance decisions
- Composite **B-tree index `(device_id, measured_at)`** — main driver of chart query speed.
- **BRIN index on `measured_at`** — append-only time-series → near-partition performance, tiny size.
- **Partitioning: deliberately NOT used.** Volume is ~260k rows/year/device; even 5 devices ×
  5 years ≈ 6.5M rows is trivial for one indexed table. Partitioning adds Flyway/maintenance
  complexity for no benefit at this scale. Schema/queries (`date_trunc` buckets) are designed
  so partitioning is a painless retrofit if device count ever grows a lot.
- **Retention:** keep raw data; scheduled job compacts raw rows older than 90 days into
  hourly averages. **Decision: implement now** (Spring `@Scheduled`, runs daily, toggleable
  via settings; compacted rows moved to `measurement_hourly` table, raw rows deleted).

---

## 6. Alert engine rules (all editable in settings)

1. **Absolute low threshold** — pressure below X PSI → alert (likely burst pipe).
   **Default: 20 PSI** (owner confirmed).
2. **Sudden drop** — drop > Y PSI within Z minutes → alert (leak starting).
   Default tuned for 2-min cadence: **>15 PSI drop within 10 minutes**.
3. **Device offline** — no data for N minutes → alert (sensor/WiFi died).
4. **Cooldown / re-arm** — one alert per incident, not per measurement; send a
   "pressure recovered" message when values normalize.

Evaluation happens on each ingested measurement (+ a scheduled check for the offline rule).

---

## 7. Dashboard features

- Big current-pressure readout, last-update time, online/offline indicator.
- Chart with granularity switch: **day view** (hourly points), **month view** (daily avg
  with min/max band), **year view** (monthly averages) + date picker.
- Settings panel: all alert rules, Telegram credentials, unit toggle (PSI/bar/kPa).
- Device picker exists in code but **hidden while only one device** is registered.
- Single-password login gate.

---

## 8. Environments, branches & CI/CD

**Git branches (owner's explicit naming choice):** `dev` and `master`.

| Branch | Railway environment | Notes |
|---|---|---|
| `dev` | `development` | Own Postgres, own API key, Telegram disabled (`TELEGRAM_ENABLED=false`) or separate test bot — no phantom "pipe burst" alerts during testing |
| `master` | `production` | Own Postgres, real Telegram bot |

- One Railway **project**, two Railway **environments** (native feature), auto-deploy per branch.
- **GitHub Actions:** on push/PR → Maven build, unit tests, Testcontainers integration
  tests; merge blocked on failure. Railway performs deployment on merge.
- Flow: feature branch → PR to `dev` (CI green) → test on dev URL → PR `dev` → `master` → prod.
- Flyway migrations run automatically on app startup in each environment.
- Note: two environments = two Postgres instances = slightly higher (still small) Railway cost. Owner informed.

### Environment variables (per Railway environment)
`DATABASE_URL` (injected by Railway), `APP_DASHBOARD_PASSWORD`, `APP_DEVICE_API_KEY`
(bootstrap key for first device), `TELEGRAM_ENABLED` (**false in dev — confirmed**),
`TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`. **Confirmed approach:** Telegram credentials
are env-seeded on first startup, then editable from the dashboard (DB-stored takes
precedence after first edit).

---

## 9. Agreed build order

1. ☑ Project skeleton (Spring Boot, Maven, Dockerfile, `railway.toml`) + Flyway migrations
2. ☑ Ingestion API + alert engine (with unit tests)
3. ☑ Aggregation API + dashboard (Chart.js)
4. ☑ Arduino/ESP32 sketch (incl. voltage-divider wiring notes + calibration)
5. ☑ GitHub Actions workflow (`dev`/`master`)
6. ☑ Deployment guide: Railway project + 2 environments, env vars, BotFather setup,
   getting Telegram chat ID, pointing ESP32 at prod URL

---

## 10. Decisions log (chronological)

| Decision | Choice | Why |
|---|---|---|
| Backend | Java 21 + Spring Boot 3 | Owner preference |
| Hosting | Railway, single service + Postgres plugin | Owner requirement; simple |
| Frontend | Chart.js SPA served by Spring Boot | One deploy, no CORS, lightweight |
| Sensor | **1.2 MPa / 174 PSI** analog 0.5–4.5 V, DC 5V, G1/4 thread | Owner's hardware (corrected from initial 100 PSI model) |
| Voltage divider | Required (ESP32 ADC ≤3.3 V) | Hardware safety/accuracy |
| Send interval | Every 1–5 min → default 2 min | Owner choice |
| Multi-device | Schema-ready now, UI hidden | Owner: "one now, design for more" |
| Partitioning | No — indexes (composite + BRIN) instead | Volume too small; retrofit-friendly design |
| Retention | Raw kept; optional 90-day compaction job | Owner approved |
| Branches | `dev` and `master` | Owner's explicit naming choice |
| Environments | Railway `development` + `production` | Owner requested CI/CD with 2 envs |
| CI | GitHub Actions (build + tests gate merges) | Railway handles deploys on merge |
| Telegram credentials | Env-seeded, then DB-editable from dashboard | Owner confirmed |
| Low-pressure default | 20 PSI | Owner confirmed |
| Telegram in dev env | `TELEGRAM_ENABLED=false` (no test bot) | Owner confirmed |
| Compaction job | Implement now (daily scheduled, 90-day cutoff, toggleable) | Owner confirmed |

---

## 11. Open items / to confirm during implementation

*(none — all planning questions resolved as of 2026-06-12; see decisions log)*
