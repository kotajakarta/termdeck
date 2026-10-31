# Perbaikan Streaming Download File Android

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Memperbaiki bug download file terpotong/korup di aplikasi Android TermDeck dengan memperbaiki handling stream EOF di server Ktor, validasi ukuran file di DownloadHelper, pencegahan pemutusan sleep OS, dan penambahan notifikasi progress download real-time.

**Architecture:** 
1. Di `FilesRoutes.kt`, perbaiki loop `streamCommandOutput` agar hanya berhenti saat `proc.inputStream.read()` bernilai `-1` (EOF standar), bukan `n <= 0`.
2. Di `DownloadHelper.kt`, tambahkan pengecekan integritas transfer (verifikasi `totalBytes == contentLength` jika header tersedia), callback progress streaming, dan pembersihan file jika transfer terputus.
3. Di `MainActivity.kt`, tampilkan notifikasi unduhan ongoing dengan progress bar di notification drawer Android, gunakan WakeLock parsial saat download aktif, dan update notifikasi menjadi "selesai" dengan ukuran valid saat berhasil.

**Tech Stack:** Kotlin, Ktor Server CIO, Android MediaStore / NotificationManager, Android JUnit tests.

**Spec:** Bug report user mengenai download file remote terpotong dan tidak bisa dibuka di aplikasi Android.

## Global Constraints
- Tetap menyimpan ke MediaStore `Downloads` (folder Download bawaan HP).
- Mempertahankan backward compatibility untuk Android API 26-34+.
- Mempertahankan semua test yang ada dan menambahkan unit test baru untuk verifikasi streaming & progress.

---

### Task 1: Perbaiki SSH Output Streaming EOF Loop di Ktor Server

**Files:**
- Modify: `android/app/src/main/java/id/termdeck/app/server/FilesRoutes.kt:924-935`
- Test: `android/app/src/test/java/id/termdeck/app/DownloadHelperTest.kt`

- [ ] Periksa dan ubah loop pembacaan `streamCommandOutput` di `FilesRoutes.kt`:
  Ganti:
  ```kotlin
  while (true) {
      val n = proc.inputStream.read(buf)
      if (n <= 0) break
      write(buf, 0, n)
      flush()
  }
  ```
  Menjadi:
  ```kotlin
  while (true) {
      val n = proc.inputStream.read(buf)
      if (n == -1) break
      if (n > 0) {
          write(buf, 0, n)
          flush()
      }
  }
  ```
- [ ] Pastikan tidak ada tempat lain di `FilesRoutes.kt` yang melakukan `read(buf)` dengan `n <= 0 break` pada stream SSH.
- [ ] Jalankan JUnit test untuk memastikan build dan test tetap lulus.

---

### Task 2: Perbaiki `DownloadHelper.kt` dengan Pengecekan Integritas dan Progress Callback

**Files:**
- Modify: `android/app/src/main/java/id/termdeck/app/DownloadHelper.kt:313-440`
- Modify: `android/app/src/test/java/id/termdeck/app/DownloadHelperTest.kt`

- [ ] Tambahkan parameter `expectedBytes: Long = -1L` dan `onProgress: ((downloaded: Long, total: Long) -> Unit)? = null` ke fungsi `saveStreamToDownloads` dan `downloadFileDirectly`.
- [ ] Di `saveStreamToDownloads`:
  - Baca `inputStream.read(buf)` dengan validasi `n == -1` sebagai EOF.
  - Panggil `onProgress?.invoke(totalBytes, expectedBytes)` secara periodik.
  - Jika `expectedBytes > 0` dan `totalBytes < expectedBytes`, lempar `IOException("Unduhan tidak lengkap: menerima $totalBytes dari $expectedBytes byte")` dan hapus file parsial yang belum selesai dari MediaStore / file system.
- [ ] Di `downloadFileDirectly`:
  - Ambil `contentLength = connection.contentLengthLong`.
  - Teruskan `expectedBytes` dan `onProgress` ke `saveStreamToDownloads`.
- [ ] Buat unit test di `DownloadHelperTest.kt` untuk memverifikasi handling stream dengan 0-byte chunk dan pemutusan stream yang belum selesai.
- [ ] Jalankan JUnit test.

---

### Task 3: Integrasikan Progress Notification & WakeLock di `MainActivity.kt` dan `AndroidManifest.xml`

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `android/app/src/main/java/id/termdeck/app/MainActivity.kt:118-151,245-287`

- [ ] Tambahkan izin `<uses-permission android:name="android.permission.WAKE_LOCK" />` di `AndroidManifest.xml`.
- [ ] Di `MainActivity.kt`:
  - Implementasikan fungsi `showDownloadProgressNotification(context, notifId, fileName, progressPercent, downloadedBytes, totalBytes)`.
  - Pasang WakeLock `PowerManager.PARTIAL_WAKE_LOCK` selama download aktif agar thread download tidak di-freeze oleh sistem operasi saat layar mati atau aplikasi di background.
  - Perbarui notifikasi secara periodik (throttled ~300-500ms) saat bytes bertambah, dan tampilkan progress bar.
  - Ketika download selesai, perbarui notifikasi ke status berhasil (`stat_sys_download_done`) dengan intent untuk membuka file.
  - Ketika download gagal, tampilkan notifikasi error dengan penjelasan dan bersihkan notifikasi progress.
- [ ] Jalankan JUnit test dan compile test.

---

### Task 4: Verifikasi Menyeluruh (Build APK & Unit Tests)

**Files:**
- Verify: Android unit tests (`testDebugUnitTest`)
- Verify: Gradle APK build (`assembleDebug`)

- [ ] Jalankan `./gradlew testDebugUnitTest` dan pastikan semua test lulus 100%.
- [ ] Jalankan `./gradlew assembleDebug` untuk memastikan APK berhasil terkompilasi tanpa error.
- [ ] Tinjau `git diff` untuk memastikan kerapian kode dan ketiadaan regresi.
