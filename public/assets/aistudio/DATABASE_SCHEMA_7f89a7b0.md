# Supalive — Suggested Database Schema

Use UUID primary keys, UTC timestamps, migrations, indexes, constraints, and owner-scoped access.

## `users`
- `id` UUID PK
- `email` unique
- `full_name`
- `avatar_url` nullable
- `is_active` boolean
- `created_at`, `updated_at`

## `projects`
- `id` UUID PK
- `user_id` FK users
- `project_name`
- `project_url`
- `project_ref`
- `publishable_key_encrypted`
- `key_version`
- `health_status`
- `monitoring_enabled` default true
- `interval_hours` default 12
- `next_check_at`
- `last_checked_at`
- `last_success_at`
- `consecutive_failures`
- `created_at`, `updated_at`

## `monitoring_jobs`
- `id` UUID PK
- `project_id` FK projects
- `job_type`
- `status`
- `scheduled_at`
- `started_at`, `completed_at`
- `locked_until`
- `attempts`, `max_attempts`
- `last_error`
- `created_at`
- Include unique/idempotency constraints to prevent duplicate scheduled jobs.

## `monitoring_logs`
- `id` UUID PK
- `project_id` FK projects
- `job_id` FK monitoring_jobs
- `check_type`
- `result`
- `http_status`
- `response_time_ms`
- `error_code`
- `error_message` sanitized
- `retry_count`
- `checked_at`

## `notifications`
- `id` UUID PK
- `user_id` FK users
- `project_id` FK projects
- `notification_type`
- `title`
- `message`
- `is_read`
- `created_at`

## `notification_preferences`
- `id` UUID PK
- `user_id` FK users
- `email_enabled`
- `in_app_enabled`
- `failure_threshold`
- `created_at`, `updated_at`

## `audit_logs`
- `id` UUID PK
- `user_id`
- `action`
- `resource_type`
- `resource_id`
- `ip_address`
- `user_agent`
- `created_at`
- Never include keys or secrets.

## `system_settings`
- `id` UUID PK
- `setting_key`
- `setting_value` JSONB
- `updated_at`

When deleting a project, cancel pending jobs and remove encrypted credentials. Retain logs according to a documented retention policy. Do not store customer database content.
