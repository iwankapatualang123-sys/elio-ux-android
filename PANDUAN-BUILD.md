# Panduan Build & Melanjutkan Pengembangan — Elio Speedo / Elio UX

Dokumen serah-terima. Isinya: cara membangun APK lewat GitHub Actions, struktur kode, aturan yang tidak boleh dilanggar, dan masalah yang pernah terjadi beserta solusinya. Ditulis untuk pemilik dan untuk AI (Gemini / Google AI Studio) yang melanjutkan pengembangan.

Status per 19 November 2026.

---

## 1. Ringkasan proyek

Aplikasi Android yang menjadikan HP/tablet sebagai **head unit mobil** (Toyota Innova Reborn). Perangkat utama: **Samsung Galaxy Tab A6 (Android 8.1, RAM 2 GB)**.

Ada **dua aplikasi** di repo yang sama, dipisah per branch:

| Branch | Aplikasi | ID aplikasi | Isi | Link APK (selalu versi terbaru) |
|---|---|---|---|---|
| `speedometer-only` | **Elio Speedo** — yang dipakai sekarang | `com.aistudio.elioux.speedo` | Hanya speedometer (4 gaya), gradasi warna, efek 3D, pop-up kunci layar saat charger dicabut. YouTube & Maps dipakai lewat split screen bawaan tablet. | https://github.com/iwankapatualang123-sys/elio-ux-android/releases/download/speedo-build/ElioSpeedo.apk |
| `main` | Elio UX (versi lama, lengkap) | `com.aistudio.elioux.launcher` | Speedometer + kartu YouTube + kartu peta (MapLibre/OpenStreetMap). | https://github.com/iwankapatualang123-sys/elio-ux-android/releases/download/latest-build/ElioUX-debug.apk |

Kedua aplikasi bisa terpasang berdampingan di satu perangkat karena ID-nya berbeda.

**Arsitektur:** aplikasi native Kotlin yang berisi satu **WebView** layar penuh. Seluruh tampilan ada di satu file HTML (`app/src/main/assets/index.html`). Kotlin (`MainActivity.kt`) membaca sensor (GPS, accelerometer, kompas, cahaya, baterai, charger) lalu mengirim datanya ke halaman lewat JavaScript, dan halaman memanggil fungsi Kotlin lewat objek `window.ElioNativeBridge`.

---

## 2. Struktur repo

```
.github/workflows/android-build.yml   Resep build otomatis GitHub Actions
app/build.gradle.kts                  Konfigurasi aplikasi (ID, SDK, dependensi, tanda tangan)
app/src/main/AndroidManifest.xml      Izin, activity, receiver (boot, admin kunci layar)
app/src/main/assets/index.html        SELURUH tampilan + logika UI (HTML/CSS/JS, ±4.100 baris)
app/src/main/java/com/example/
  MainActivity.kt                     WebView, sensor, GPS, charger, kunci layar, jembatan JS
  BootReceiver.kt                     Buka aplikasi otomatis saat perangkat menyala
  LockAdminReceiver.kt                Izin "Administrator perangkat" (hanya kunci layar)
app/src/main/res/xml/device_admin.xml Kebijakan admin: force-lock saja
app/src/main/res/values/strings.xml   Nama aplikasi & teks izin admin
gradle/libs.versions.toml             Daftar versi library
gradle.properties                     Pengaturan Gradle (AndroidX, cache, dll.)
settings.gradle.kts / build.gradle.kts  Pengaturan proyek Gradle
PRD-Elio-UX.md                        (jika ada) dokumen kebutuhan produk untuk build ulang
```

File yang **tidak dipakai saat build** (sisa dari Google AI Studio, aman diabaikan): `metadata.json`, `public/assets/aistudio/*`, `.env.example`, `README.md`.

---

## 3. Versi alat build (jangan diubah sembarangan)

| Komponen | Versi | Catatan |
|---|---|---|
| Android Gradle Plugin (AGP) | 9.1.1 | AGP 9 punya **Kotlin bawaan**: jangan menambahkan plugin `org.jetbrains.kotlin.android` di `app/build.gradle.kts` |
| Gradle | 9.3.1 | Dipasang oleh workflow |
| Kotlin | 2.4.21 | Hanya dideklarasikan `apply false` di `build.gradle.kts` root untuk mengunci versi compiler |
| JDK di CI | 17 (Temurin) | |
| compileSdk / targetSdk | 36.1 / 36 | |
| minSdk | 24 | Tab A6 = API 27 |
| Library | `core-ktx`, `appcompat`, `play-services-location` | Hanya tiga ini. Jangan bawa Firebase/Room/Retrofit/Compose dari template AI Studio — tidak dipakai dan membuat APK 26 MB |

---

## 4. Cara kerja build otomatis (GitHub Actions)

File: `.github/workflows/android-build.yml`. Berjalan otomatis setiap ada **push** ke branch `main` atau `speedometer-only`, atau dijalankan manual.

Langkah-langkahnya:

1. **Checkout** kode.
2. **Pasang JDK 17.**
3. **Prepare environment file** — menyalin `.env.example` ke `.env` (sisa AI Studio; tidak berpengaruh).
4. **Install Android command line tools** — mengunduh `commandlinetools-linux` resmi dari Google ke `$HOME/android-sdk`. (SDK bawaan runner GitHub tidak dipakai karena tidak konsisten.)
5. **Install SDK packages** — `platform-tools`, `platforms;android-36`, `build-tools;36.0.0`, dan menerima lisensi.
6. **Set up Gradle 9.3.1.**
7. **Prepare fixed signing key** — jika secret `SIGNING_KEYSTORE_BASE64` ada, didekode jadi file kunci. Jika tidak ada, muncul peringatan dan APK ditandatangani kunci debug acak.
8. **Build debug APK** — `gradle :app:assembleDebug --no-daemon`.
9. **Choose release target** — branch `speedometer-only` → `ElioSpeedo.apk` di rilis `speedo-build`; branch lain → `ElioUX-debug.apk` di rilis `latest-build`.
10. **Upload artifact** dan **Publish release** — APK diunggah ke rilis GitHub dengan tag tetap, jadi **link download tidak pernah berubah**; isinya ditimpa build terbaru dari branch itu.

Lama build: sekitar 3–6 menit.

---

## 5. Cara membangun APK (langkah praktis)

### Cara A — Ubah kode lalu push (normal)

1. Edit file di branch yang benar (`speedometer-only` untuk Elio Speedo).
2. Commit dan push ke GitHub (lewat GitHub Desktop, git, editor web GitHub, atau ekspor dari AI Studio ke repo ini).
3. Build berjalan otomatis.

### Cara B — Jalankan manual tanpa mengubah kode

1. Buka repo di GitHub → tab **Actions**.
2. Pilih workflow **Build Android APK** di kiri.
3. Klik **Run workflow** → pilih branch (`speedometer-only` atau `main`) → **Run workflow**.

### Memantau hasil build

1. Tab **Actions** → klik run teratas.
2. ✅ hijau = sukses, ❌ merah = gagal.
3. Jika gagal: klik job **build** → buka langkah yang bertanda ❌ (biasanya **Build debug APK**) → scroll ke bagian **"What went wrong:"**. Pesan error inti ada di sana, **bukan** di daftar dependensi panjang di bagian bawah. (Log hanya bisa dibuka saat login GitHub.)

### Memasang di tablet

1. Buka link APK di bagian 1 lewat browser tablet → unduh → pasang (izinkan "Install dari sumber tidak dikenal").
2. Jika muncul **"Aplikasi tidak terpasang"**: uninstall versi lama dulu (lihat bagian 6).
3. Pertama kali dibuka: izinkan **Lokasi**, lalu **Aktifkan** admin perangkat (untuk kunci layar).
4. **Jangan jadikan Elio Speedo sebagai aplikasi Home default** jika ingin split screen — Android tidak mengizinkan aplikasi Home dibagi layarnya.

---

## 6. Kunci tanda tangan tetap (disarankan)

Tanpa kunci tetap, setiap build ditandatangani kunci acak → update **harus uninstall dulu** dan pengaturan hilang.

Kunci sudah dibuat dan disimpan **di PC pemilik, di luar repo**: `C:\Users\NTHA\Documents\elio-ux-signing\`

- `elioux-signing.p12` — file kunci (alias `elioux`)
- `ISI-GITHUB-SECRETS.txt` — nilai yang perlu diisi ke GitHub

Cara mengaktifkan (sekali):

1. GitHub → repo → **Settings → Secrets and variables → Actions → New repository secret**.
2. Buat dua secret dari isi `ISI-GITHUB-SECRETS.txt`:
   - `SIGNING_STORE_PASSWORD`
   - `SIGNING_KEYSTORE_BASE64`
3. Jalankan build ulang. Update pertama setelah ini masih perlu uninstall sekali; sesudahnya cukup timpa.

⚠️ **Jangan pernah** menempel isi file itu ke chat AI, ke repo, atau ke dokumen. Simpan cadangan folder tersebut (Google Drive pribadi / flashdisk). Jika hilang, update berikutnya harus uninstall lagi.

Di `app/build.gradle.kts`, blok `signingConfigs` bertuliskan "Do not remove or modify… AI Studio" — biarkan apa adanya. Kunci tetap dipasang lewat blok terpisah `elioFixed` di bagian bawah file, dan hanya aktif jika environment variable dari CI ada.

---

## 7. Peta kode: di mana mengubah apa

### Tampilan — `app/src/main/assets/index.html`

| Bagian | Cari teks / id |
|---|---|
| Variabel warna tema | `:root {` → `--theme-accent`, `--theme-accent-2`, `--card-bg-*` |
| Utility CSS offline (pengganti Tailwind) | `<style id="offline-tailwind">` — kelas Tailwind yang belum ada di sini **tidak akan berfungsi**; tambahkan definisinya di blok ini |
| Efek kartu speedometer (grid 3D, glow, bingkai gradasi) | `.speedo-card`, `.speedo-fx`, `.speedo-grid-plane` |
| Pop-up kendaraan mati | `#vehicleOffPrompt`, `.vehicle-off-prompt`, fungsi `showVehicleOffPrompt` |
| Mode Hemat | `body.eco-mode` (CSS), `applyEcoMode` (JS) |
| 4 gaya speedometer | `id="cluster_lfa"`, `cluster_m3`, `cluster_mazda`, `cluster_honda` |
| Gradasi pada SVG | `<linearGradient id="elioAccentGrad">` — semua garis/jarum memakai `url(#elioAccentGrad)` |
| Menggerakkan jarum & angka | fungsi `updateSpeed(val)` |
| Ganti gaya | `setSpeedometerStyle(style)`, `cycleSpeedometerStyle()`; daftar gaya: `gaugeStyleOrder` |
| Gradasi warna custom | `applyThemeGradient(c1, c2, save)`; preset di Pengaturan (`.grad-chip`) |
| Penyimpanan tema | `restoreThemeState`, `rememberThemeCall`, kunci localStorage `elioTheme` |
| Pengaturan | `id="cockpitSettingsDeck"`, tab `settingsTab_theme/audio/power/vehicle` |
| Inisialisasi saat dibuka | cari `// Initial setup` di dekat akhir file |

### Android — `MainActivity.kt`

- **Sumber halaman:** `index.html` disajikan dari alamat palsu `https://<ID aplikasi>/index.html` lewat `shouldInterceptRequest` (lihat aturan 8.3).
- **GPS:** satu sumber, `FusedLocationProviderClient` 1 detik; cadangan GPS biasa bila Play Services tidak ada.
- **Charger:** `powerAndBatteryReceiver` → `startAutoLockCountdown()` (cabut) / `cancelAutoLock("charger")` (pasang).
- **Kunci layar:** `lockDeviceNow()` → `DevicePolicyManager.lockNow()`, butuh admin aktif. Izin diminta otomatis sekali di `askLockAdminOnFirstRun()` (flag `lock_admin_asked` di SharedPreferences `elio_prefs`).
- **Split screen:** `onPause` tidak menjeda WebView bila `isInMultiWindowMode` (agar speedometer tetap hidup).

### Jembatan halaman → Android (`window.ElioNativeBridge.*`)

`getGpsSpeed()`, `getGForce()`, `isAccOn()`, `requestSensorSync()`, `requestLocationPermission()`, `resetTripOdometer()`, `isLockAdminActive()`, `requestLockAdmin()`, `removeLockAdmin()`, `confirmVehicleOff()`, `cancelVehicleOff()`, `testVehicleOffPrompt()`, `setKeepScreenOn(bool)`, `ensureFullscreen()`, `openGoogleMapsIntent(tujuan)`, `openYouTubeVideo(id)`, `launchExternalApp(key)` — key: `maps`, `youtube`, `waze`, `spotify`, `phone`, `dashcam`.

### Android → halaman (fungsi yang dipanggil Kotlin)

`window.onNativeGpsUpdate(...)`, `window.onNativeSensorUpdate(...)`, `window.onAccPowerStateChanged(isOn)`, `window.onVehicleOffPrompt(detik, adminAktif)`, `window.onVehicleOffPromptClosed(alasan)`, `window.onLockAdminStatus(aktif)`, `window.onNativeGpsPermissionDenied()`.

Saat halaman dibuka di browser biasa (tanpa Android), `window.ElioNativeBridge` tidak ada dan aplikasi berjalan dalam **mode simulasi** — berguna untuk mencoba tampilan.

---

## 8. Aturan penting (pelajaran dari masalah yang sudah terjadi)

1. **Jangan memakai CDN** untuk CSS/JS/font. Head unit sering kehilangan sinyal; semua harus di dalam `index.html`.
2. **Jangan memakai `backdrop-filter: blur`** dan jangan menambah animasi terus-menerus di banyak elemen — Tab A6 (GPU lemah) jadi patah-patah dan sentuhan terasa mati.
3. **YouTube embed:** halaman harus punya origin HTTPS yang **bukan** youtube.com. Dari `file://` → *Error 153*; mengaku `www.youtube.com` → *Error 152-4*. Origin yang benar: `https://<ID aplikasi>` (sudah diterapkan).
4. **Overlay yang tidak aktif harus `display:none`** (kelas `hidden`), bukan sekadar transparan. Lapisan transparan di atas layar pernah membuat **semua tombol tidak bisa ditekan**. Tulis CSS yang valid (`pointer-events: none;`, bukan `pointer-events-none;`).
5. **Satu sumber GPS saja.** Dulu GPS + Network + Fused berjalan bersamaan dan membanjiri thread utama.
6. **Jangan memuat ulang peta/iframe secara berkala.** Itu penyebab utama tersendat saat berkendara.
7. **Mode Hemat hanya menghentikan animasi.** Jangan matikan bayangan/glow statis di Mode Hemat — tampilan jadi datar (Mode Hemat menyala otomatis di perangkat RAM ≤ 2 GB).
8. **Daftar gaya speedometer harus sesuai elemen yang ada** (`lfa`, `m3`, `mazda`, `honda`). Gaya yang tidak ada membuat kartu kosong.
9. **Tidak ada data palsu.** Jangan menampilkan angka contoh seolah nyata. Catatan: panel samping gaya Lexus/Mazda/Honda masih berisi angka contoh statis (mis. "Cruising Range 540 km", "Avg Fuel 14.8 km/L", "Tire Pressure 33 PSI") — kandidat perbaikan berikutnya: ganti dengan data asli (kecepatan maks, rata-rata, waktu perjalanan) atau hapus.
10. **Jangan menambah API berbayar / API key** (pemilik menolak Google Cloud). Untuk peta native gunakan solusi tanpa key, atau cukup buka aplikasi Google Maps (`openGoogleMapsIntent`).
11. **Build CI hijau ≠ fitur berjalan.** Selalu uji di browser (mode simulasi) dan di tablet asli.

---

## 9. Error build yang pernah terjadi & solusinya

| Gejala di log | Penyebab | Solusi |
|---|---|---|
| `contains AndroidX dependencies, but the android.useAndroidX property is not enabled` | `gradle.properties` tidak ada / tidak lengkap | Pastikan `android.useAndroidX=true` ada di `gradle.properties` |
| Langkah **Set up Android SDK** gagal (action `android-actions/setup-android`) | Action pihak ketiga bermasalah di runner terbaru | Sudah diganti: unduh cmdline-tools manual (langkah 4 workflow). Jangan kembalikan action itu |
| `sdkmanager` gagal / tidak ditemukan | SDK bawaan runner tidak konsisten | Sama seperti di atas — pakai cmdline-tools yang diunduh sendiri |
| `Unable to resolve action ...@vX` | Tag versi action tidak ada | Cek tag yang tersedia di halaman GitHub action tersebut |
| Error terkait plugin Kotlin / Compose / KSP | Template AI Studio menambah plugin yang tidak dipakai | Hapus pluginnya; AGP 9 sudah punya Kotlin bawaan |
| `Missing google-services.json` / secrets plugin | Plugin Firebase/secrets dari template | Tidak dipakai — jangan ditambahkan kembali |
| Build sukses tapi APK tidak bisa dipasang ("Aplikasi tidak terpasang") | Tanda tangan berbeda dari versi terpasang | Uninstall dulu, atau pasang kunci tetap (bagian 6) |

---

## 10. Menguji tanpa build

- **Di PC:** buka `app/src/main/assets/index.html` langsung di Chrome/Edge. Aplikasi masuk mode simulasi (kecepatan bergerak sendiri). Tombol **Uji Pop-up** di Pengaturan → Daya menampilkan pop-up versi demo.
- **Screenshot otomatis (Windows, Edge bawaan):**
  ```
  "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" --headless=new --disable-gpu --hide-scrollbars --virtual-time-budget=6000 --window-size=1280,800 --screenshot=hasil.png "file:///C:/Users/NTHA/Documents/GitHub/elio-ux-android/app/src/main/assets/index.html"
  ```
  Ganti `--window-size=640,800` untuk melihat tampilan split screen.
- **Checklist di tablet setiap rilis:** speedometer mengikuti GPS saat berkendara; ganti 4 gaya (ketuk speedometer); gradasi & tema tetap tersimpan setelah aplikasi ditutup; split screen dengan Maps/YouTube; cabut charger → pop-up → "Tidak" batal, "Ya" kunci, diam 10 detik kunci; pasang charger saat hitung mundur → batal.

---

## 11. Template perintah untuk Gemini

Salin ke Gemini saat melanjutkan:

> Kamu melanjutkan proyek Android "Elio Speedo" di repo GitHub `iwankapatualang123-sys/elio-ux-android`, branch `speedometer-only`. Baca dulu `PANDUAN-BUILD.md` di root repo, terutama bagian 7 (peta kode) dan 8 (aturan penting). Tampilan ada di `app/src/main/assets/index.html`, kode Android di `app/src/main/java/com/example/MainActivity.kt`. Build dilakukan otomatis oleh GitHub Actions setiap push ke branch ini; jangan ubah `.github/workflows/android-build.yml`, versi Gradle/AGP, atau blok `signingConfigs` kecuali diminta. Jangan menambah library, API key, atau CDN. Target perangkat Galaxy Tab A6 (Android 8.1, RAM 2 GB), jadi utamakan ringan. Tugas saya: …
