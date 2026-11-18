# Supalive — Architecture

## Recommended stack
- Frontend: Next.js App Router, TypeScript, Tailwind CSS, shadcn/ui, Lucide.
- API: Python FastAPI, Pydantic, SQLAlchemy 2, Alembic, HTTPX.
- Supalive own database: PostgreSQL.
- Scheduler and worker: separate long-running Python processes.
- Persistent job queue: PostgreSQL using `FOR UPDATE SKIP LOCKED`; Redis is not required for MVP.
- Deployment: Ubuntu 24.04 VPS, Docker Compose, Nginx, HTTPS.

## Services
1. `web`: user interface.
2. `api`: authentication, project CRUD, logs, statistics, notifications.
3. `scheduler`: discovers due projects and enqueues persistent jobs.
4. `worker`: claims jobs and performs health checks.
5. `postgres`: Supalive application database.

The scheduler and worker must run independently of browser sessions. Do not implement the scheduler only in client-side JavaScript or serverless functions with no guaranteed execution.

## Scheduler rules
- Default cadence is exactly 12 hours.
- Each monitoring-enabled project is scheduled.
- Use a stable randomized phase/initial offset per project to spread load, then preserve the 12-hour cadence without cumulative random drift.
- A scheduler transaction creates a unique due job and advances `next_check_at`.
- Disable/delete stops future pending jobs where possible; an already-running request may finish.
- Enable should validate configuration and schedule the next check.
- Use per-project serialization to avoid overlapping checks.

## Worker
- Claim jobs with PostgreSQL `FOR UPDATE SKIP LOCKED`.
- Use leases and stale-job recovery.
- Ensure idempotency.
- Limit global concurrency and per-project concurrency.
- Request timeout: 10 seconds.
- Maximum retries: 2 retries (3 total attempts).
- Retry transient network/timeouts and HTTP 429, 500, 502, 503, 504 with exponential backoff plus jitter.
- Do not repeatedly retry 401/403, invalid URL/key, missing table, or RLS misconfiguration.
- Retry scheduling must not shift the regular 12-hour cadence.

## Monitoring request
GET `/rest/v1/supalive_health?id=eq.1&select=id`
Headers:
- `apikey: <project_publishable_key>`
- `Accept: application/json`

Expect HTTP 200 and a JSON array containing the expected row. Follow current Supabase API requirements; never use service_role or secret keys.

## Suggested API routes
Base `/api/v1`

Auth:
- POST `/auth/register`
- POST `/auth/login`
- POST `/auth/logout`
- POST `/auth/refresh`
- POST `/auth/forgot-password`
- POST `/auth/reset-password`
- GET `/auth/me`

Projects:
- GET/POST `/projects`
- GET/PATCH/DELETE `/projects/{id}`
- POST `/projects/{id}/validate`
- POST `/projects/{id}/check`
- POST `/projects/{id}/enable`
- POST `/projects/{id}/disable`

Monitoring:
- GET `/monitoring/overview`
- GET `/monitoring/logs`
- GET `/monitoring/logs/{id}`
- GET `/monitoring/statistics`

Notifications:
- GET `/notifications`
- PATCH `/notifications/{id}/read`
- POST `/notifications/read-all`
- GET/PATCH `/notifications/preferences`

Settings:
- GET/PATCH `/settings/profile`
- DELETE `/settings/account`
