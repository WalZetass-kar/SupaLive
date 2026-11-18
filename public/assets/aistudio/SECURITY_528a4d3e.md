# Supalive — Security Requirements

## Credential handling
- Accept only Supabase Publishable Key (`sb_publishable_...`) and optionally legacy anon key if explicitly supported.
- Never request or accept `sb_secret`, `service_role`, database passwords, or PostgreSQL connection strings.
- Encrypt stored keys using AES-256-GCM with a unique nonce and versioned ciphertext.
- Keep encryption master key in environment secrets/secret manager; support key rotation.
- Decrypt only in worker memory during a check.
- Never return encrypted/decrypted credentials in API responses or logs.
- Do not place credentials in job payloads; jobs should contain project/job IDs only.

## SSRF controls
- Require HTTPS.
- Allow only official Supabase Cloud hosts matching `*.supabase.co` (or a separately verified custom domain policy).
- Validate hostname and project reference.
- Reject localhost, private/link-local IPs, IP-literal hosts, unsupported ports, URL credentials, and redirects.
- Resolve DNS and reject private/link-local addresses; repeat validation before each request to mitigate DNS rebinding.
- Enforce request timeout and response size limits.

## Application security
- Every project operation must be scoped to authenticated user; never trust `user_id` supplied by client.
- Use secure, HttpOnly, SameSite cookies where applicable; CSRF protection for cookie-authenticated mutations.
- HTTPS, HSTS, strict CORS, rate limits, Pydantic validation, parameterized SQL.
- Audit actions without secrets.
- Avoid overly revealing error messages.
- Tests: SSRF, DNS rebinding, cross-user access, key leakage, invalid sessions, SQL injection, rate limits, duplicate jobs, stale-job recovery, encryption/decryption.

## Health check boundaries
- Read-only GET only.
- Never write to customer business tables.
- Never insert artificial business records.
- Never execute arbitrary SQL.
- Never use secret/service-role credentials.
