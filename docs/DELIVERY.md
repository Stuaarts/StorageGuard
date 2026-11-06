# Delivery status

First implementation delivered November 3, 2026. The app is an MVP development build, with the release checks below still open. The source brief is preserved unchanged in the repository. Its referenced companion IDE prompt was absent; implementation follows the supplied brief.

## Implemented

- Native Kotlin, Jetpack Compose and Material 3 with teal light/dark themes, bottom navigation, onboarding, dashboard, scanning, searchable/filterable findings, review, final confirmation, results/history and settings.
- Android 11+ scoped MediaStore photos/videos, Android 14 selected-media coverage, Photo Picker, individual documents and recursive SAF folder selection with persisted access.
- Platform-independent size bucketing, full streaming SHA-256, known-alias deduplication, explicit uncertainty for unknown provider identities, cancellable scans and cooperative pause/resume within the current process.
- Room latest inventory and duplicate groups, recent scan sessions, interrupted-scan status, DataStore preferences and minimal local cleanup history.
- No automatic selection/removal. Explicit keep/remove controls, fresh metadata/hashes/byte comparison, maximum 50-copy batch, OS-approved media trash and off-by-default permanent local document deletion.
- Provider outcome reconciliation, approval/denial handling, persisted pending requests, unverified outcomes for missing evidence, no claims of measured reclaimed storage.
- Reset of local index/history/preferences and saved grants, no network permission/backend/account/analytics, backup/transfer exclusions.
- Public [StorageGuard Privacy Policy](https://stuaarts.github.io/StorageGuard/) on GitHub Pages, linked from Settings in version `0.1.1` (build `2`). Android opens it in a browser.
- Gradle wrapper with distribution checksum, exported Room schema, GitHub Actions build/lint/test workflow and project documentation.

## Verification

Verification uses JDK 17, SDK 36 and isolated Android 11, 14 and 16 emulators. The [security and stability review](SECURITY_STABILITY_REVIEW.md) records the source audit, fixes, manual checks and release limits.

| Check | Result |
| --- | --- |
| `:core:test` | Passed, 32 tests |
| `:app:assembleDebug` | Passed; installable development APK |
| `:app:lintDebug` | Passed; dependency update and optional KTX style warnings remain, no errors |
| `:app:assembleRelease` | Passed with R8; unsigned release APK, no release credentials configured |
| `:app:connectedDebugAndroidTest` | Passed, all 16 tests on each of APIs 30, 34 and 36 |
| Onboarding, settings and activity recreation | Passed on API 36 emulator |
| Real MediaStore discovery, hashing, Room persistence and changed-content refusal | Passed on API 36 emulator |
| Android trash approval and retained-copy verification | Passed on API 36 emulator |
| Live emulator cleanup of the prepared sample pair | Passed; copy trashed, explicitly protected original readable with unchanged SHA-256, rescan shows no duplicates; [manual record](MANUAL_CLEANUP_CHECK.md) |
| Android trash denial and both-copy retention | Passed on API 36 emulator |
| Interrupted pending request reconciled on launch | Simulated interruption passed in CI. An actual API 34 process termination during consent also verified recovery and preservation of the protected original |
| Local folder grant, alias deduplication and reviewed document deletion | Passed on API 36 emulator; parent-folder absence checked after deletion |
| Initial layout checks | Welcome/review screens inspected; dashboard inspected in light/dark themes and at 200% font on API 36; full accessibility matrix remains open |
| GitHub-hosted workflow | All four jobs passed on source commit `50df1ac`, including the built release APK manifest check. [Build and emulator results](https://github.com/Stuaarts/StorageGuard/actions/runs/41768162345) |

Generated test and lint reports are under each module's ignored `build/reports` directory. Build outputs are under `app/build/outputs/apk`. Test assertions use synthetic files; no personal file contents are committed.

The GitHub run also publishes downloadable development APK and report artifacts. Hosted action-version deprecation notices remain nonblocking; future CI maintenance should update those actions. Documentation-only commits do not rerun Android jobs. Local commits and the configured remote are synchronized.

## Before a production release

1. Complete [the device/version/provider matrix](TESTING.md), especially permission revocation during I/O, physical-device SAF behavior and removable volumes. Android 11 permission denial and Android 14 selected-photo access have emulator coverage.
2. Exercise real process termination during scans and repeat consent interruption on physical devices. Android 14 emulator coverage includes real process loss with consent open.
3. Check TalkBack, 200% system font, narrow devices, tablets and both themes on real screens. Actions are allowed to wrap and file lists scroll, but a full accessibility audit is pending.
4. Profile large libraries and multi-gigabyte videos on physical devices. There are no measured median-time, crash-rate or recovered-space claims yet.
5. Perform the share-workflow experiment in [RESEARCH.md](RESEARCH.md). WhatsApp is not installed/tested in this environment, and the product does not assume every share creates a duplicate.
6. Supply release signing outside Git, review app-store policy/permission eligibility, prepare store assets and make a separate release decision. Nothing is published to an app store by this workflow.

## Deliberate limits

Scans are foreground app work and restart after process death. Background scheduling and incremental/checkpoint scanning are future work. Only standard local documents with known identity and supported write/delete grants are removable; generic/cloud providers and unresolvable picker aliases are review-only. Unknown-size files are indexed but not hashed. SHA-256 groups establish content equality at scan time; cleanup requires a new verification. Android cannot lock external shared files through its consent dialog, so simultaneous external changes remain a documented platform race.

Perceptual/similar-photo matching, accounts, cloud sync, forensic recovery, private-cache access, expert/root diagnostics and secure-erasure claims are outside this MVP.
