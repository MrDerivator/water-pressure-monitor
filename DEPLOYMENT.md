# Deployment Guide

End-to-end setup: GitHub → Railway (two environments) → Telegram → ESP32.

## 1. GitHub repository

1. Create a new GitHub repo and push this project.
2. Create the two branches:
   ```bash
   git checkout -b master && git push -u origin master
   git checkout -b dev && git push -u origin dev
   ```
3. In GitHub → Settings → Branches, add branch protection for `master` (and optionally `dev`):
   require the **CI** check to pass before merging. The workflow in
   `.github/workflows/ci.yml` runs the Maven build, unit tests, and the Testcontainers
   integration test on every push/PR to `dev` and `master`.

## 2. Railway project

1. Go to railway.app → **New Project** → **Deploy from GitHub repo** → pick your repo.
   Railway detects the `Dockerfile` via `railway.toml`.
2. The default environment is `production`. In the service settings, set the
   **deploy branch to `master`**.
3. Add PostgreSQL: **+ New** → **Database** → **PostgreSQL**.
4. On the app service → **Variables**, add:

   | Variable | Value |
   |---|---|
   | `JDBC_DATABASE_URL` | `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}` |
   | `JDBC_DATABASE_USERNAME` | `${{Postgres.PGUSER}}` |
   | `JDBC_DATABASE_PASSWORD` | `${{Postgres.PGPASSWORD}}` |
   | `APP_DASHBOARD_PASSWORD` | a strong password for the dashboard login |
   | `APP_DEVICE_API_KEY` | a long random string (e.g. `openssl rand -hex 32`) — the ESP32 sends this |
   | `APP_DEVICE_NAME` | `Main water line` (or whatever you like) |
   | `TELEGRAM_ENABLED` | `true` |
   | `TELEGRAM_BOT_TOKEN` | from BotFather (step 4) |
   | `TELEGRAM_CHAT_ID` | your chat ID (step 4) |

   These seed the database on first startup; afterwards thresholds and Telegram settings
   are edited from the dashboard's Settings panel.

5. Generate a public domain for the service (Settings → Networking → Generate Domain).
   The dashboard lives at that URL; health check at `/api/health`.

## 3. The `development` environment

1. In the Railway project, open the **environment switcher** (top bar) → **New Environment**
   → name it `development`.
2. Railway duplicates the services. In the duplicated app service, set the
   **deploy branch to `dev`**.
3. The duplicated Postgres is a separate instance with its own data — exactly what we want.
4. Set the same variables as production but with:
   - `TELEGRAM_ENABLED` = `false` (owner-confirmed — no phantom alerts from testing)
   - a different `APP_DEVICE_API_KEY` and `APP_DASHBOARD_PASSWORD`
5. Generate a separate domain for the dev service.

Workflow from here: feature branch → PR to `dev` (CI must pass) → auto-deploys to the
dev URL → when happy, PR `dev` → `master` → auto-deploys to production.

## 4. Telegram bot

1. In Telegram, message **@BotFather** → `/newbot` → follow prompts → copy the **bot token**.
2. Open a chat with your new bot and send it any message (bots can't message you first).
3. Get your chat ID: open
   `https://api.telegram.org/bot<YOUR_TOKEN>/getUpdates`
   in a browser and read `result[0].message.chat.id`.
4. Put the token and chat ID into the production environment variables (or directly into
   the dashboard Settings panel later).

## 5. ESP32

1. Wire the sensor **through the voltage divider** — see the comment block at the top of
   `arduino/pressure_sensor/pressure_sensor.ino`. Never connect the sensor signal
   directly to the ESP32.
2. Copy `arduino/pressure_sensor/` into your Arduino IDE sketchbook, create `secrets.h`:
   ```c
   #define WIFI_SSID     "your-wifi"
   #define WIFI_PASSWORD "your-password"
   #define API_URL       "https://<your-production-domain>/api/measurements"
   #define API_KEY       "<APP_DEVICE_API_KEY value>"
   ```
3. Board: any ESP32 dev module. Flash, open Serial Monitor at 115200, verify
   `Sent xx.xx PSI -> 201` lines.
4. For first tests, point `API_URL` at the **dev** environment domain and use the dev API key.

## 6. Smoke test without hardware

```bash
curl -X POST https://<domain>/api/measurements \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: <APP_DEVICE_API_KEY>" \
  -d '{"pressurePsi": 55.3}'
```
Then open the dashboard — the gauge and the day chart should show the reading.
To test alerting end-to-end, post a value below your low threshold (default 20 PSI)
and check Telegram.

## 7. Local development

```bash
docker run -d --name wm-pg -e POSTGRES_DB=watermonitor -e POSTGRES_USER=watermonitor \
  -e POSTGRES_PASSWORD=watermonitor -p 5432:5432 postgres:16-alpine
mvn spring-boot:run
# dashboard: http://localhost:8080  (password: changeme)
```
Tests: `mvn verify` (needs Docker running for the Testcontainers integration test).
