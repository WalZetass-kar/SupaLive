# Supalive — Google AI Studio Starter Pack

Paket ini berisi spesifikasi produk, arsitektur, SQL health-check, dan prompt implementasi untuk membangun **Supalive** menggunakan Google AI Studio.

## Cara pakai
1. Ekstrak ZIP ini.
2. Buka Google AI Studio: https://aistudio.google.com/
3. Masuk ke **Build** dan buat aplikasi baru.
4. Buka `prompts/GOOGLE_AI_STUDIO_BUILD_PROMPT.md`.
5. Salin prompt tersebut ke AI Studio. Lampirkan atau berikan isi dokumen di `docs/` jika AI Studio meminta konteks tambahan.
6. Minta AI Studio membangun aplikasi secara bertahap. Periksa hasil dan uji sebelum deployment.

## Konfigurasi produk yang sudah ditetapkan
- Interval default: setiap 12 jam (2 kali per hari per project).
- Target: seluruh project terdaftar dengan monitoring aktif.
- Scheduler/worker berjalan di server 24/7; tidak bergantung browser atau user login.
- Metode registrasi: Supabase Project URL + Publishable Key.
- Health check: request GET read-only ke Supabase REST API.
- Semua project perlu menyiapkan tabel health-check menggunakan SQL yang tersedia.
- Tidak menjanjikan bahwa request ini pasti mencegah Supabase melakukan pause.
- Project yang sudah paused perlu di-resume melalui dashboard resmi Supabase.

## Catatan penting
Google AI Studio dapat membantu membangun aplikasi, tetapi scheduler 24/7 memerlukan backend yang selalu aktif dan deployment server. Jangan mengandalkan browser atau frontend AI Studio sebagai scheduler.
