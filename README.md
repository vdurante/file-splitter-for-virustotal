<p align="center">
  <img src=".github/logo.svg" width="128" height="128" alt="File Splitter for VirusTotal logo">
</p>

<h1 align="center">File Splitter for VirusTotal</h1>

<p align="center">
  <b>Scan APKs of any size with VirusTotal, right from your Android phone.</b><br>
  Too big for the 650 MB upload limit? It splits them into scannable pieces automatically.
</p>

<p align="center">
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white">
  <a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/License-MIT-8AA4F5"></a>
</p>

<p align="center">
  <sub>Unofficial. Not affiliated with or endorsed by VirusTotal or Google. "VirusTotal" and its logo are
  trademarks of their owners. Bring your own free VirusTotal API key.</sub>
</p>

---

## Features

- 🧩 **Splits oversized APKs:** anything over 650 MB becomes a slim APK plus as few bundle APKs as
  possible, each still analyzed by VirusTotal as an Android package.
- ⚡ **Hash first, upload second:** files VirusTotal already knows are answered instantly by SHA-256.
- 🚦 **Respects the free API:** 4 requests/minute, with automatic retries for quota and network errors.
- 🔁 **Resilient queue:** one file at a time, per-file Retry, "Retry failed", and a pause-and-fix
  banner for a rejected API key.
- 🌙 **Works in the background:** progress notification, and saved progress you can **Resume** after
  the app is closed.
- 🔒 **Private:** your API key never leaves the device, and the app talks only to `virustotal.com`.

## How it works

```mermaid
flowchart LR
    A[Pick an APK] --> B{Over 650 MB?}
    B -- no --> U[Submit as-is]
    B -- yes --> S[Move the largest files out]
    S --> SL[Slim APK<br/>everything else]
    S --> BU[Bundle APKs ≤ 650 MB<br/>moved files + manifest]
    U & SL & BU --> H[SHA-256 lookup]
    H -- known --> R[Report]
    H -- unknown --> UP[Upload and analyze] --> R
```

1. Paste your VirusTotal API key (stored on the device only) and pick an APK.
2. **Under 650 MB:** the APK is submitted as-is.
3. **Over 650 MB:** the largest files are moved out until the rest fits, producing:
   - a **slim APK** with everything else, and
   - **bundle APKs** (up to 650 MB each) holding the moved files at their original paths, plus the
     original `AndroidManifest.xml` so VirusTotal still analyzes them as APKs. Files are packed
     into as few bundles as possible.
4. Files are processed one at a time: SHA-256 → hash lookup → upload only if VirusTotal doesn't
   know the file yet → wait for the analysis.

Each row shows its status (waiting, running, clean, detections, error), whether the result came
from an **upload** or a **hash match**, a link to the VirusTotal report, and Retry when it failed.

An APK is a zip file, so splitting never decompiles anything: entries are copied as they are.
Slim and bundle APKs aren't re-signed; they're for scanning, not installing.

## Build

Requirements: Android Studio (or JDK 21 and the Android SDK) and a device or emulator with
Android 8.0 or newer.

```bash
./gradlew :app:installDebug      # build and install a debug build on the connected device
./gradlew :app:assembleRelease   # release build (configure your own signing key first)
```

## Test files

`scripts/make_test_apk.sh` builds test APKs from this app's own debug APK:

| File | Purpose |
|---|---|
| `small_test.apk` | A real APK under 650 MB, submitted directly |
| `small_test_salted.apk` | The same plus a random file, so its hash is new every run (forces an upload) |
| `big_test.apk` | About 1.5 GB: the APK plus three 500 MB files in nested folders, so it gets split |

```bash
PUSH=1 scripts/make_test_apk.sh                         # also copy them to the device's Downloads
ANDROID_SERIAL=<serial> PUSH=1 scripts/make_test_apk.sh  # pick a device when several are connected
```

`scripts/make_bundle_apk.sh` builds a bundle by hand, to check how VirusTotal classifies one.

## Project structure

```
app/src/main/java/io/github/vdurante/filesplitter/
├── MainActivity.kt      home screen, or the current scan's results
├── FileSplitterApp.kt   holds the ScanEngine; starts ScanService while it is busy
├── api/                 VirusTotal HTTP client and rate limiter
├── apk/                 zip reading; choosing files to move; writing slim and bundle APKs
├── scan/                ScanEngine (the whole flow), ScanService, models, saved state
└── ui/                  Compose screens and components
scripts/                 test-file helpers
```

## Privacy

- The API key stays in the app's private storage and is excluded from backups.
- Uploaded files become available to the VirusTotal community. Don't submit confidential APKs.
- The app only talks to `virustotal.com`.

## Limitations

- Split APKs (`.apks`, `.xapk`) aren't supported yet.
- A single file over 650 MB (as stored in the APK) can't be submitted.

## License

[MIT](LICENSE)
