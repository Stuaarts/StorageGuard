# Testing

## Automated commands

Use JDK 17, Android SDK 36 and Build Tools 35.0.0. SDK packages/licenses must be installed in Android Studio before the first build. The checked-in wrapper verifies the Gradle distribution checksum.

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :app:assembleRelease
```

Start a disposable Android 11+ emulator or connect a development device before instrumentation. Tests create and delete their own `sg-test-*` media under `Pictures/StorageGuardTests`; use a test device because the UI scan can also discover other permitted files. Tests grant media access, exercise actual provider I/O, and interact with Android consent. They do not approve removal of unrelated device files. The release APK is unsigned until release signing is configured; no signing keys belong in Git.

The JVM suite covers renamed exact copies, same-size different bytes, alias deduplication, uncertain providers, keep-first selection, nondeletable providers, changed content/metadata, missing keeper, empty files, unreadable candidates, cancellation and short-read/large-stream comparison. Cleanup evidence tests ensure request success, consent approval and inaccessible rows cannot be treated as verified removal.

Instrumentation covers real MediaStore discovery and streams, Room result persistence, stale-content preflight refusal, onboarding, access/settings navigation, activity recreation, approved/denied Android trash, simulated interrupted-request reconciliation, real SAF folder grants, MediaStore/SAF alias deduplication and explicitly reviewed local document deletion. Further cases and the exact completed runs are listed in [delivery status](DELIVERY.md).

The expanded suite also checks resource limits, live hash cancellation/closure, changed or missing copies through the UI, unavailable evidence, scan interruption, reset without file deletion, and repeated launcher intents. CI tests APIs 30, 34 and 36, and checks the built release manifest using `scripts/check_apk_policy.py`. Current security/stability evidence and remaining limits are recorded in [SECURITY_STABILITY_REVIEW.md](SECURITY_STABILITY_REVIEW.md).

## Device release gate

Run the following matrix on Android 11/12, Android 13, Android 14 with selected access, and Android 16, including at least one physical device and removable storage where supported. An API 36 emulator alone does not validate every version/provider.

| Scenario | Expected result |
| --- | --- |
| Deny all media permissions | Accurate coverage; document selection and navigation remain available |
| Android 14 partial photos/video selection | Only selected accessible rows scanned; reselect route works |
| Revoke permissions while discovering/hashing | Reads stop or become issues; consistent partial result; no cleanup assumed |
| Duplicate media plus overlapping SAF folder | Underlying known local asset counted once |
| Local selected documents/folder | Recursive discovery, persisted grant survives restart; default review-only deletion setting |
| Enable advanced local document deletion | Explicit permanent warning and exact list; kept copy survives; absent document separately checked |
| Generic cloud or nondeletable provider | Comparison possible when readable; review-only removal; no independent-copy savings |
| Edit/move/delete keeper or target after scan | Fresh verification blocks changed or unreadable pair |
| Cancel Android trash dialog | Per-file skipped/unverified outcome; no inferred removal |
| Approve Android trash dialog | Actual `IS_TRASHED` queried; retained copy remains readable |
| Kill app while scanning or in consent | Interrupted session; pending request reconciled conservatively on next launch |
| Pause/cancel with large video | Cooperative streaming behavior; streams closed; restart available |
| Rotate/recreate on review, confirmation and consent | Selection stays with ViewModel where applicable; pending operations never silently repeated |
| Dark mode, narrow screen, tablet, 200% font, TalkBack | Readable text, wrapped actions, understandable selected/protected/error states |
| Reset local app data | Index/history/preferences gone; persisted grants released; user files untouched |
| Trash with unchanged device free bytes | Report moved to trash; never assert measured recovered space |

## Corpus and measurement

Use byte-identical renamed photos, videos, archives, PDFs and zero-byte files; same-size unequal content; recompressed images; inaccessible documents; and a multi-gigabyte video. Compare on-device hashes with a separate trusted digest tool for sample files. Record file count, candidate count, bytes read, elapsed time, completion/failure status and crash observations. Do not log user names, paths, hashes or file content in analytics. Large-device profiling, crash-free rates and median scan times require real repeated runs; no measured performance claims are made yet.

## Share workflow research

The WhatsApp/share-copy hypothesis is unvalidated. Sharing can grant access to an existing URI instead of creating a new persistent file. Follow [RESEARCH.md](RESEARCH.md) and report only accessible inventory evidence.
