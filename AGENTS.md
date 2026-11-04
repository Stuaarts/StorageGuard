# StorageGuard engineering guidance

Read the product brief and `docs/ARCHITECTURE.md` before changing storage or cleanup behavior. This is a Kotlin/Compose Android app with a reusable JVM scan core, not a web app. Use JDK 17 and the checked-in Gradle wrapper.

- Keep all processing local. Do not add networking, analytics, all-files access, account flows or automatic deletion without an explicit product decision.
- A protected independent readable copy must pass fresh content verification before removal. Unknown identities are review-only; filename, size and preview similarity are never duplicate proof.
- Do not infer removed files or reclaimed space from request/consent success. Reconcile provider evidence and retain explicit unverified outcomes.
- Preserve MediaStore/SAF URI grants, Android partial-media coverage and process-interruption behavior. Avoid raw shared-storage filesystem access.
- Update README and delivery status when functionality or verification status changes. Record limitations and remaining device checks honestly.
- Run `:core:test :app:assembleDebug :app:lintDebug` for relevant changes; run instrumentation when changing UI, storage or cleanup flows. Check release compilation when changing build configuration.
- Keep source, Room schemas, documentation and CI configuration in Git. Exclude local SDK paths, generated builds, emulator data and signing secrets. Use focused commits and push completed work as requested by the project owner.
