# Architecture

StorageGuard is an Android 11+ application. There is no server component. Compose renders immutable `AppState` from `StorageViewModel`; user actions flow back through that ViewModel. A small application container supplies the storage adapter, Room database and DataStore preferences.

## Modules and data flow

| Location | Responsibility |
| --- | --- |
| `core/Scanner.kt` | Platform-independent inventory normalization, full streaming SHA-256, exact groups, keep policy and fresh byte comparison |
| `core/CleanupEvidence.kt` | Conservative interpretation of provider cleanup evidence |
| `app/storage/AndroidStorage.kt` | MediaStore enumeration, recursive SAF traversal, URI streams, metadata refresh and provider operations |
| `app/data/Database.kt` | Room scan sessions, latest file index, groups and minimal cleanup audit |
| `app/data/Preferences.kt` | DataStore onboarding, selected sources and advanced deletion preference |
| `app/StorageViewModel.kt` | Scan lifecycle, UI state, preflight, persisted cleanup requests and reconciliation |
| `app/MainActivity.kt` | Compose themes, navigation, access launchers, preview intents and screens |

Scans run on `Dispatchers.IO` in a ViewModel coroutine. Discovery normalizes file metadata and removes known aliases before size bucketing. Only known nonnegative sizes with at least two candidates are hashed. Hashing is sequential, with 128 KiB buffers, cancellation checks and no media decoding. Metadata is checked before and after each hash, and streamed length must match metadata. Hash collisions in size + full SHA-256 produce review groups. Preflight additionally compares bytes before cleanup.

Progress is throttled to about ten updates per second. Pause cooperatively stops discovery emission and hashing reads; cancellation closes streams. Some provider query/read calls can block until the provider returns. A new scan restarts inventory and clears earlier findings; it does not reuse incomplete hashes. Partial provider failures are surfaced and successful comparisons remain usable.

`ScanLimits` bounds retained inventory to 30,000 records and 4,000,000 metadata characters, with a 4,096-character per-field cap. Each tree traversal permits up to 4,096 unique directories, 1,000,000 retained ID characters and 100,000 rows. IDs are deduplicated before enqueueing. Reaching a budget stops discovery with a visible partial-coverage reason and hashes only the retained candidates. Unknown-size files consume the same inventory budget. Streams that exceed their advertised size are rejected after at most one extra byte; streams returning zero progress fail rather than spin. Pause checkpoints apply to directory traversal as well as file/hash work.

## Identity and coverage

MediaStore assets use volume plus relative location/name when available, with a volume/row ID fallback. Standard local ExternalStorageProvider document IDs map to the same volume/path identity, preventing MediaStore and SAF from counting the same accessible asset twice. Full hash equality alone never establishes independent copies.

Generic document providers and Photo Picker URI aliases have no reliable physical identity contract in this implementation. They can be compared and inspected, but cannot be selected for removal or counted as independent retained copies. This sacrifices some possible savings deliberately. Provider flags and current write grants must also permit local SAF deletion.

Media access is recomputed on resume. Android 14 selected-media access is displayed separately from full photos/video access. MediaStore queries run only for the granted categories, or the accessible selected subset. SAF roots and individual documents are optional, persisted URI grants. A folder may overlap the media library; known identities prevent double-counting. No raw filesystem paths are opened by the app.

## Cleanup sequence

1. The user protects a copy and explicitly selects extras. No copies are preselected. A maximum of 50 copies is accepted per batch.
2. Final review lists the protected copies, every selected URI and the applicable trash/permanent impact.
3. `KeepPolicy` checks membership, independence, supported removal and a retained copy in each group. The ViewModel prioritizes the user-protected copy as the keeper.
4. Preflight reopens both keeper and target, checks metadata, hashes both, compares bytes, and checks metadata again. A failed initial preflight blocks the entire batch.
5. Local SAF documents require the advanced deletion setting, a second per-document preflight and explicit in-app confirmation. Android does not provide the MediaStore trash approval flow for these document operations. Each request is recorded before invocation.
6. Media is reverified after any document operations. Requested attempts are persisted before launching Android's `createTrashRequest` consent UI.
7. Reconciliation checks `IS_TRASHED`, including trashed rows. Consent approval alone is never success. Unavailable rows/access become `UNVERIFIED`, not removed. SAF success requires provider acceptance plus a separate absence check; standard local tree documents can be checked against the still-readable parent if the deleted URI no longer resolves. An inaccessible parent stays unverified. Interruption becomes unverified.
8. Terminal audit rows omit names, URIs and content hashes. The current session's results can show in-memory names/locations alongside each outcome, without saving them to history. Findings are invalidated after completed cleanup. The user rescans to review remaining files.

Android has no transaction locking arbitrary external provider files across hashing and the system consent dialog. Other apps or users can still modify or remove a keeper after preflight. The app cannot promise an atomic, immutable retained copy while another app changes shared files. It fails closed for observed changes and uncertainty; users should avoid simultaneous edits during cleanup. This is a platform limit, not a secure-erasure guarantee.

## Local persistence and lifecycle

Room stores one current inventory, up to 20 recent scan sessions and 200 recent terminal cleanup attempts. Pending requests retain only the URIs needed for reconciliation. They are reconciled at the next launch if the process dies. A scan marked running when a fresh ViewModel loads is marked interrupted. Activity recreation keeps the existing ViewModel; process death restarts a scan from scratch.

Scans are user-initiated, in-app work. There is no background service, periodic scan or WorkManager job in this release; WorkManager is reserved for a future explicitly requested deferred-work feature. Background continuation and checkpoint resume after process death are not promised. Local app data reset clears the index/history/preferences and releases saved document grants. Android media permissions remain controlled by the OS.

Database schema 1 is exported under `app/schemas`. Future schema changes must add explicit Room migrations. Backups and device transfer of app data are excluded. The database is private app storage, not separately application-encrypted. External previews are delegated to the user's selected viewer with a temporary read grant.
