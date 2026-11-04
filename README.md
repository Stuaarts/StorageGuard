# StorageGuard

StorageGuard helps Android owners understand redundant files and safely review exact duplicates. Its goal is simple: **find what your phone stores, understand where copies live, and remove only what you choose.**

The MVP is a native Kotlin / Jetpack Compose app for Android 11 and newer. It processes accessible files on the device, with no account, backend, advertising, analytics, or file uploads. Detection uses full streaming SHA-256 hashes, with a fresh byte comparison before cleanup. It never deletes automatically.

## Project status

Initial implementation is in progress, following [the product brief](StorageGuard_Android_MVP_Brief.docx). The companion IDE prompt named in that brief was not supplied. Implementation and verification status will be recorded in [docs/DELIVERY.md](docs/DELIVERY.md).

## Planned MVP

- Clear permission and scan coverage information, including Android 14 selected-media access.
- MediaStore photos and videos, selected documents, and user-granted folders.
- Cancellable scans, exact duplicate findings, filtering, explicit protected copies, and file details.
- Revalidation before user-approved media trash or supported permanent document deletion.
- Local scan index and minimal cleanup history, with honest per-file results.

## Platform boundaries

Only granted, supported files can be analyzed. Other apps' private caches and databases are inaccessible. StorageGuard does not recover deleted files, bypass Android permissions, or guarantee secure erasure. A URI is not proof of a separate physical file; uncertain provider identities are excluded from removable-copy calculations. Candidate logical bytes are not verified reclaimed space. Moving files to trash can leave storage usage unchanged.

## Build

Install Android Studio, JDK 17, Android SDK 36, and SDK Build Tools 35.0.0. Set `ANDROID_HOME` to your SDK directory or provide an untracked `local.properties` with `sdk.dir`. The Gradle wrapper pins Gradle 8.13; Android Gradle Plugin is 8.11.1, Kotlin is 2.2.20, and the minimum Android API is 30.

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
```

Open the repository in Android Studio to run `app`, or install `app/build/outputs/apk/debug/app-debug.apk` using `adb install -r`. Never commit signing keys or `local.properties`.

## Development

`core` owns platform-independent scanning, duplicate grouping, identity and safety rules. `app` contains ContentResolver adapters, Room storage, DataStore preferences, the ViewModel, and Compose screens. See [architecture](docs/ARCHITECTURE.md), [device checks](docs/TESTING.md), and [privacy](docs/PRIVACY.md) as implementation proceeds.
