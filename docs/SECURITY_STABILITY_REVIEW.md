# Security and stability verification

Review started November 3, 2026. This record distinguishes observed checks from release assurance; it is not a guarantee of security under every condition.

## Source audit and hardening

Codex Security completed an independent baseline review, architecture review, focused counter-review and parent validation of repository revision `036b76c`. The scan covered 39 tracked source/document files; the upstream Gradle wrapper binary was separately integrity-checked against Gradle's published SHA-256. Third-party library implementation, OS/OEM internals and Git history were not comprehensively audited.

The audit identified one low-severity local availability issue: a user-selected document provider could return unbounded unique metadata, exhausting scan memory. It requires provider selection and a user-initiated scan; no deletion, disclosure or privilege-escalation path was found through it. The focused counter-review assessed it as reliability-only; the final audit conservatively retained a narrowly scoped low-severity availability finding.

The subsequent implementation adds file-count, retained-metadata, field-length, traversal-row and directory budgets, directory enqueue deduplication, visible partial results and overlong/zero-progress stream rejection. The original sealed scan records the pre-hardening source; regression tests verify the new controls. Generic providers remain review-only, and keep-first/preflight/consent checks are preserved.

No supported deletion-consent bypass, index-upload path, embedded credential/private-key exposure, SQL injection or incoming-intent cleanup command was found in reviewed first-party source. This is a review result, not proof that no unknown vulnerability exists.

## Checks completed so far

| Check | Observed result |
| --- | --- |
| Core JVM suite | 32 tests passed, including 15 added hardening/failure tests |
| Android 16 / API 36 suite | 15 instrumented tests passed together after fixing a folder-picker test timing race |
| Debug and optimized release builds | Passed |
| Android lint | Passed, nonblocking update/style notices remain |
| Actual release manifest | No Internet/all-files permission, backups disabled, not debuggable/testOnly, expected exported launcher and OS-protected profile receiver only |
| Gradle wrapper | Matches published 8.13 wrapper SHA-256 `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f` |
| Runtime dependency advisories | OSV returned no matches for 79 resolved Maven runtime components on November 3, 2026 |
| Android 11 / API 30 and Android 14 / API 34 | Additional emulator runs in progress |
| Hosted CI for the expanded checks | Configuration updated; final run pending |

The dependency query used [OSV's package/version API](https://google.github.io/osv.dev/post-v1-querybatch/). It sent public component coordinates and versions, not user files or indexed metadata. Database coverage can be incomplete and new advisories can appear. Toolchain plugins, native platform vulnerabilities and unreported dependency defects are not certified by that result.

## Regression coverage

Core checks cover exact/unequal content, alias identities, unknown/nondeletable providers, every-copy selection refusal, stale/missing copies, metadata limits, partial findings, traversal duplicates/cycles/row limits, cancellation with stream closure, short reads, overlong streams, zero-progress streams, content changes between hashing and final comparison, revoked removal capability, unknown lengths, and the maximum cleanup batch.

Android checks cover real MediaStore streams, persisted index, permission/manifest policy, approved and denied trash, unavailable pending-media evidence, interrupted document and scan records, activity recreation during review, keeper/selection changes, changed-target and missing-keeper refusal before system consent, pause/cancel/restart, user-data-preserving local reset, real SAF grant persistence/removal, alias deduplication, bounded traversal and reviewed permanent document deletion.

Run the merged-manifest gate after `:app:assembleRelease`:

```powershell
python scripts/check_apk_policy.py --apkanalyzer "$env:ANDROID_HOME/cmdline-tools/16.0/bin/apkanalyzer.bat"
```

Any installed Android command-line-tools version providing `apkanalyzer` is acceptable; on CI it is discovered from PATH. The script inspects the built artifact rather than assuming the source manifest is the complete merged result.

## Remaining limits

- Physical devices, OEM-specific providers, removable media, full TalkBack/tablet checks and sustained large-library profiling still require the release matrix in [TESTING.md](TESTING.md).
- Shared files can change after preflight while Android consent is open; the platform provides no transaction lock across providers.
- Cancellation is cooperative. A provider blocked inside query/open/read may not return promptly; this is not solved by a coroutine timeout alone.
- Reset clears logical local records and attempts URI-grant release. It is not a forensic-erasure promise, and OS-managed media permissions remain outside app reset.
- The database is app-private, with backup/transfer exclusions, but is not separately application-encrypted. External preview handlers and selected cloud providers have their own behavior.
- Production signing and app-store release are not configured; the test APK uses development signing.

Codex Security's tool-reported usage for the completed scan was 10,661,257 total tokens, including 10,360,192 cached input tokens, across five recorded threads. These are tool-provided accounting values, not an independent cost estimate.
