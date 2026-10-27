# File Splitter for VirusTotal

An Android app that scans APK files with [VirusTotal](https://www.virustotal.com), including APKs
larger than VirusTotal's 650 MB upload limit.

> Unofficial. Not affiliated with or endorsed by VirusTotal. "VirusTotal" and its logo
> are trademarks of their owners. You need your own (free) VirusTotal API key.

## How it works

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

The app also:
- stays within the public API limit (4 requests per minute) and retries quota and network errors;
- pauses on a rejected API key and lets you fix it in place;
- keeps working in the background (foreground service with a progress notification);
- saves progress, so after the app is killed you can reopen it and tap **Resume**.

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
