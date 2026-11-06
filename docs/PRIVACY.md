# Privacy

The public [StorageGuard Privacy Policy](https://stuaarts.github.io/StorageGuard/) is linked from the app's Settings. Edit [the published source](privacy/index.html) on `main`, then deploy it with `git subtree push --prefix=docs/privacy origin gh-pages`.

StorageGuard compares files that you permit it to read. There is no login, backend, advertising SDK, analytics SDK or app Internet permission. The app does not upload file content, hashes, names or locations.

## What stays on the device

The local scan index contains source URIs, provider identities where established, display names, MIME types, sizes, modification times, source locations, SHA-256 values and duplicate-group membership. Recent scan sessions record time, scope, status, counts and errors. Pending cleanup requests temporarily retain selected URIs so the app can check interrupted operations. Completed history keeps operation IDs, times, action, status and logical byte counts, with names, URIs and hashes omitted.

No content previews or full files are saved in app logs or in the database. Preview opens a compatible viewer you choose; that viewer has its own privacy behavior. Explicitly selected cloud providers may download a file using their own network access before supplying its stream. StorageGuard itself supplies no upload or cloud synchronization feature.

## Access and removal

Media permissions and Android 14 selected-media access control library scope. Optional system pickers grant access to selected files or folders. The app does not request all-files access or root. It cannot read other apps' private caches/databases or recover deleted files. Folder removal releases saved access and clears findings. Android Settings can revoke media permissions.

Cleanup never runs automatically. Media removal uses Android's approved trash request and verifies the resulting trash state. Supported local document deletion is an off-by-default advanced feature and may be permanent. Identical content does not guarantee another app no longer needs its own reference to the copy. Unknown identities and unsupported deletion providers are review-only.

## Retention and reset

Only the latest scan inventory is retained, alongside up to 20 recent sessions and 200 recent completed cleanup attempts. Pending attempts are kept until reconciled. Settings → Reset local app data erases the local index, recent history and preferences and releases persisted picker grants. It does not remove your files or revoke OS-managed media permissions. App backups and device transfers are excluded through manifest and backup rules. Uninstalling removes app-private data under Android's normal behavior.

Candidate savings describe logical file bytes. The app does not measure or promise physical storage recovery, forensic erasure or a particular trash retention period. Trash restoration and expiry are handled by Android and the relevant provider.
