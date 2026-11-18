# Prompt untuk Google AI Studio — Build Supalive

Buat aplikasi web full-stack bernama **Supalive**, platform dashboard untuk mendaftarkan project Supabase dan menjalankan database health-check otomatis setiap 12 jam. Gunakan dokumen dalam paket ini sebagai requirement utama.

## Tujuan dan konfigurasi wajib
- Project didaftarkan menggunakan nama, Supabase Project URL, dan Publishable Key.
- Semua project yang terdaftar dan `monitoring_enabled=true` masuk scheduler.
- Interval default 12 jam, yaitu 2 request per hari per project.
- Scheduler dan worker harus berjalan 24/7 di backend/server meskipun user logout, browser ditutup, atau frontend tidak dibuka.
- Request harus benar-benar memanggil Supabase REST Database API, bukan sekadar ping root URL.
- Gunakan health-check table `public.supalive_health` dan endpoint `GET /rest/v1/supalive_health?id=eq.1&select=id`.
- Jangan mengklaim request pasti mencegah Supabase pause. Jangan implementasikan auto-resume.
- Project yang sudah paused perlu di-resume melalui dashboard resmi Supabase.
- Gunakan Publishable Key/anon key saja. Jangan meminta secret/service_role key atau database password.

## UI/UX
Buat UI modern, premium, clean, dan responsif; jangan terlihat seperti template AI generik.
- Gunakan Next.js App Router + TypeScript + Tailwind CSS + shadcn/ui + Lucide.
- Dashboard memiliki sidebar/nav, overview cards, project list/table, status badges, monitoring toggle, log activity, dan detail project.
- Sediakan halaman login/register, dashboard, add-project flow, project detail/logs, notifications, settings.
- Gunakan loading, empty, error, success states.
- Jangan menggunakan data palsu sebagai data operasional. Jika demo mode diperlukan, tandai jelas.
- Jangan menampilkan credential dalam plaintext setelah disimpan.

## Backend and architecture
Implementasikan backend FastAPI + Python, PostgreSQL milik Supalive, SQLAlchemy 2, Alembic, HTTPX.
Pisahkan service `api`, `scheduler`, `worker`, `web`, dan `postgres`. Gunakan Docker Compose untuk deployment.
Jangan mengandalkan browser timer, client-side JS, atau serverless cron yang tidak menjamin eksekusi untuk scheduler 24/7.

## Scheduler requirements
- Default interval 12 jam.
- Stable randomized initial phase per project untuk menyebar beban, lalu cadence 12 jam harus tetap konsisten tanpa cumulative random drift.
- Scheduler membuat persistent jobs secara transactional dan idempotent; update `next_check_at`.
- Worker claim jobs via PostgreSQL `FOR UPDATE SKIP LOCKED`, menggunakan lease, stale-job recovery, per-project serialization, dan concurrency limit.
- Request timeout 10 detik.
- Maksimal 2 retry tambahan (3 total attempts).
- Retry transient network errors, timeout, HTTP 429/500/502/503/504 dengan exponential backoff + jitter.
- Jangan retry terus untuk 401/403, key/URL salah, tabel tidak ada, atau RLS misconfigured.
- Retry tidak boleh menggeser jadwal utama 12 jam.
- Disable monitoring menghentikan job terjadwal berikutnya; enable melakukan validasi dan menjadwalkan ulang.
- Delete project membatalkan pending jobs dan menghapus credential terenkripsi.

## Security (mandatory)
- Encrypt keys at rest with AES-256-GCM, unique nonce, key version; master key from environment secret.
- Decrypt only in worker memory; never log, return, or include key in job payload.
- HTTPS only, secure session handling, CSRF as applicable, strict CORS, rate limits, input validation, parameterized SQL.
- SSRF defenses: allow only verified official Supabase HTTPS domains, reject localhost/private/link-local/IP literals/unsupported ports/URL userinfo/redirects, DNS validation and revalidation.
- Scope all project queries to authenticated owner; do not trust client-supplied user ID.
- Health-check is read-only. Never use secret key, arbitrary SQL, or write to customer business tables.
- Sanitise logs and errors.

## Status and logging
Statuses: `healthy`, `warning`, `unreachable`, `credential_error`, `configuration_error`, `suspected_paused`, `monitoring_disabled`.
Do not infer paused from one generic error. Repeated failures may produce `suspected_paused`, clearly described as an estimate.
Logs include timestamp, project, HTTP status, latency, result, sanitized error, retry count. Never include API keys.
Dashboard includes total projects, healthy/warning/unreachable, checks today, success rate, average latency, last check, last success, next check, and recent activity.

## Build process and deliverables
1. First inspect current project/workspace and identify existing files. Do not overwrite working code blindly.
2. Create a clear implementation plan and then implement the app.
3. Include database migrations/schema, environment example, Docker Compose, API, UI, scheduler, worker, and tests.
4. Include README with local setup, environment variables, migration, SQL setup for customer Supabase projects, and deployment steps.
5. Include tests for authentication, project ownership isolation, encryption, SSRF validation, scheduler cadence/idempotency, retries, stale-job recovery, and health-check response validation.
6. Run available tests and lint/type checks. Fix errors before finishing.
7. If Google AI Studio environment cannot run persistent backend processes, still create the complete deployable backend and clearly explain how to run it on a VPS. Do not fake scheduler behavior in the frontend.
8. Present a concise summary of implemented files, run commands, remaining limitations, and deployment instructions.

Follow `docs/PRODUCT_REQUIREMENTS.md`, `docs/ARCHITECTURE.md`, `docs/DATABASE_SCHEMA.md`, and `docs/SECURITY.md` in this ZIP. If any implementation requirement conflicts with security, choose the secure approach and document it.
