# Manual cleanup verification

Passed on November 3, 2026, at approximately 9:20 PM America/Toronto, using the running `StorageGuard_Test` Android 16 / API 36 emulator and the installed 0.1.0 development APK.

## Setup and scope

Only synthetic PNG files under `Pictures/StorageGuardDemo/` were involved. At the start, `sample-original.png` was already in Android trash from an earlier operation, while `sample-copy.png` was active. The original was restored as test setup before this cleanup run. Both files then had identical full SHA-256 hashes and sizes of 614,809 bytes.

The app's default protected copy was `sample-copy.png`. The test explicitly chose **Keep this** on `sample-original.png`, then selected only `sample-copy.png` for removal. The app does not infer which file is an original from its name.

## Executed flow

1. Ran a fresh scan, which found one two-file exact duplicate group.
2. Protected `sample-original.png` and selected `sample-copy.png`.
3. Checked the final confirmation: the protected list named the original, and the removal list contained only the copy, MediaStore item 91.
4. Chose **Verify & request Android approval**. Android's own dialog identified `sample-copy.png` and requested approval to move it to trash.
5. Approved the Android dialog. StorageGuard reported **Trashed · media trash** for `sample-copy.png`.
6. Independently queried MediaStore with trashed rows included, read the retained original to recompute its SHA-256, and checked the unrelated sample image.
7. Ran another scan. Findings reported zero exact duplicate groups in the granted media scope.

## Observed result

| Synthetic asset | MediaStore identity | Final state | Content check |
| --- | --- | --- | --- |
| `sample-original.png` | `external_primary/images/media/93` | Active, `IS_TRASHED=0`, readable, 614,809 bytes | SHA-256 unchanged |
| `sample-copy.png` | `external_primary/images/media/91` | `IS_TRASHED=1`, moved to Android trash | Removed from active scan results |
| `sample-different.png` | `external_primary/images/media/92` | Active, `IS_TRASHED=0`, readable, 614,717 bytes | SHA-256 unchanged |

The original and duplicate shared this pre-cleanup SHA-256; the retained original had the same digest afterward:

```text
6cb2a87624cb1dd12f1c3f620071ca2f51f3371fc93574aea0784d02c076e0e7
```

The unrelated image remained unchanged:

```text
324896e1fa1423dc421d21dc3db207811e33ad47c732ec7f758b25e4a5f9cff6
```

The emulator was left open on cleanup history. The duplicate remains in Android trash; this test did not permanently erase it or measure recovered physical storage. This verifies the observed synthetic-file cleanup run on API 36, alongside the existing automated tests; broader device/provider checks remain in [TESTING.md](TESTING.md).
