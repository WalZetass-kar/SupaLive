# Supalive — Product Requirements Document

## 1. Ringkasan
Supalive adalah platform untuk mendaftarkan project Supabase, menjalankan pemeriksaan database terjadwal, menampilkan status dan riwayat, serta memberi notifikasi saat pemeriksaan gagal.

Tujuan utama adalah menyediakan health check berkala yang nyata terhadap Supabase REST Database API. Fitur ini bukan jaminan untuk mengubah klasifikasi aktivitas internal Supabase.

## 2. Pengguna dan alur utama
1. Pengguna membuat akun dan login.
2. Pengguna mendaftarkan project dengan nama, Project URL, dan Publishable Key.
3. Aplikasi memvalidasi URL, key, dan konfigurasi health-check.
4. Project aktif dimasukkan ke scheduler.
5. Worker menjalankan GET health-check setiap 12 jam.
6. Hasil, waktu respons, dan error yang sudah disanitasi dicatat.
7. Dashboard menunjukkan status, check terakhir, check berikutnya, statistik, dan log.
8. Pengguna dapat mengaktifkan atau menonaktifkan monitoring per project.

## 3. Fitur MVP
- Register, login, logout, reset password, session management.
- CRUD project.
- Validasi project dan tombol manual check.
- Monitoring otomatis dengan interval 12 jam.
- Dashboard overview.
- Activity logs dan statistik.
- Status project: healthy, warning, unreachable, credential_error, configuration_error, suspected_paused, monitoring_disabled.
- Notifikasi in-app; email dapat disiapkan sebagai opsi.
- Pengaturan profil dan preferensi notifikasi.
- Responsif desktop dan mobile.

## 4. Dashboard
Tampilkan:
- Total project.
- Project healthy, warning, unreachable.
- Check hari ini.
- Success rate.
- Average response time.
- Recent activity.
- Untuk setiap project: status, last checked, last success, next check, consecutive failures, monitoring toggle.

## 5. Non-functional requirements
- Job queue persisten; pekerjaan tidak hilang saat proses restart.
- Idempotency dan recovery untuk job yang stale.
- Worker dapat diskalakan secara horizontal.
- Setiap query project harus dibatasi berdasarkan user yang login.
- Kredensial dienkripsi at-rest.
- Proteksi SSRF, rate limiting, input validation, dan audit log.
- Jangan menyimpan isi tabel bisnis pengguna.
- Jangan pernah menampilkan atau mencatat key rahasia.

## 6. Di luar MVP
- OAuth Supabase.
- Membuat project Supabase otomatis.
- Auto-resume project paused.
- Billing/payment.
- Team management.
- Backup atau arbitrary SQL execution.

## 7. Batasan dan disclosure
Health check yang berhasil hanya membuktikan endpoint dan tabel yang dikonfigurasi dapat dibaca. Ini tidak menjamin Supabase menganggap project aktif atau mencegah pause. Jangan mendeteksi status paused hanya dari satu error generik.
