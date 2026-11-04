# StorageGuard

[![Android checks](https://github.com/Stuaarts/StorageGuard/actions/workflows/android.yml/badge.svg)](https://github.com/Stuaarts/StorageGuard/actions/workflows/android.yml)

StorageGuard helps Android owners understand redundant files and safely review exact duplicates. Its goal is simple: **find what your phone stores, understand where copies live, and remove only what you choose.**

The MVP is a native Kotlin / Jetpack Compose app for Android 11 and newer. It processes accessible files on the device, with no account, backend, advertising, analytics, or file uploads. Detection uses full streaming SHA-256 hashes, with a fresh byte comparison before cleanup. It never deletes automatically.

## Project status

The first working MVP is implemented from [the product brief](StorageGuard_Android_MVP_Brief.docx). It includes the scanner, review screens, Android-approved trash, optional local document deletion and local persistence. It is a development build, with physical-device and multi-version release checks still required. See [delivery status](docs/DELIVERY.md) for tested behavior and remaining release work. The companion IDE prompt named in the brief was not supplied.

## What it does

- Clear permission and scan coverage information, including Android 14 selected-media access.
- MediaStore photos and videos, selected documents, and user-granted folders.
- Pause/resume within a live scan, cancellation/restart, exact findings, search/category filters, explicit protected copies, and file details/previews.
- Revalidation before user-approved media trash or supported permanent document deletion.
- Local scan index and minimal cleanup history, with honest per-file results.

## Try it

1. Install the debug APK on Android 11 or newer. Continue through the local-processing explanation.
2. Grant full or selected photos/video access, or use Settings to choose documents and folders.
3. Start a scan. The device storage summary and analyzed coverage are displayed separately.
4. Open an exact-match group. One independent copy is protected by default; you can choose a different keeper. Select individual extras; no removals are preselected.
5. Review the protected copies and exact removal list. Media requires Android trash approval. Permanent local document deletion is disabled until you enable the advanced setting and explicitly confirm the final list.
6. Check the verified outcomes in History, then rescan for fresh findings. Reset local app data in Settings to clear the index/history and saved location grants.

Files from uncertain/generic providers remain review-only. Opening a preview delegates temporary read access to your chosen viewer. Selected cloud providers may retrieve a file through their own connection; StorageGuard has no Internet permission or upload functionality.

## Platform boundaries

Only granted, supported files can be analyzed. Other apps' private caches and databases are inaccessible. StorageGuard does not recover deleted files, bypass Android permissions, or guarantee secure erasure. A URI is not proof of a separate physical file; uncertain provider identities are excluded from removable-copy calculations. Candidate logical bytes are not verified reclaimed space. Moving files to trash can leave storage usage unchanged.

Shared files can change in another app while Android's consent dialog is open. Android offers no cross-provider transaction lock; StorageGuard checks fresh metadata, hashes and bytes before requesting removal and refuses observed uncertainty. Keep other apps from editing selected files during cleanup. Scans run in the app and survive activity recreation; interrupted processes require restarting the scan.

Scans retain at most 30,000 file records and enforce metadata and provider traversal budgets. Reaching a limit produces explicit partial coverage; choose smaller folders to analyze the remainder. These bounds protect against excessive provider responses. A provider that blocks inside a query/read may still delay cancellation until that call returns.

## Build

Install Android Studio, JDK 17, Android SDK 36, and SDK Build Tools 35.0.0. Set `ANDROID_HOME` to your SDK directory or provide an untracked `local.properties` with `sdk.dir`. The Gradle wrapper pins Gradle 8.13; Android Gradle Plugin is 8.11.1, Kotlin is 2.2.20, and the minimum Android API is 30.

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

Open the repository in Android Studio to run `app`, or install `app/build/outputs/apk/debug/app-debug.apk` using `adb install -r`. Never commit signing keys or `local.properties`.

On macOS/Linux use `bash ./gradlew` instead. If Java selection is wrong, point `JAVA_HOME` to a JDK 17 installation. Initial dependency downloads require network access on the development machine; the app itself has no network permission. Release output from `:app:assembleRelease` is unsigned until a private signing configuration is supplied. This repository does not publish to an app store automatically.

## Development

`core` owns platform-independent scanning, duplicate grouping, identity and safety rules. `app` contains ContentResolver adapters, Room storage, DataStore preferences, the ViewModel, and Compose screens. See [architecture](docs/ARCHITECTURE.md), [device checks](docs/TESTING.md), and [privacy](docs/PRIVACY.md).

The GitHub Actions workflow builds debug/release variants, runs core tests and lint, and runs emulator instrumentation. Generated APKs and reports are build artifacts, not committed source. Keep implementation milestones in focused commits and push completed, checked work to the configured Git remote.

The workflow also checks the actual merged release manifest for unexpected permissions, debugging, backups and exported components, and runs device tests on Android API 30, 34 and 36. See [security and stability verification](docs/SECURITY_STABILITY_REVIEW.md) for measured results and remaining limits.

## Android references

The access model follows Android's [selected-media guidance](https://developer.android.com/about/versions/14/changes/partial-photo-video-access), [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files) and [MediaStore APIs](https://developer.android.com/reference/android/provider/MediaStore). Toolchain compatibility is documented in [AGP 8.11 release notes](https://developer.android.com/build/releases/agp-8-11-0-release-notes). Sharing does not always create another file; the proposed messaging workflow is tracked as unvalidated research in [docs/RESEARCH.md](docs/RESEARCH.md).
