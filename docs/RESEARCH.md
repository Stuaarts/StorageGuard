# Share workflow research

The product does not assume that sending media creates another persistent file. The following experiment is a manual release research task and has not been performed in this repository's implementation environment.

Record Android version, device, messaging app/version, granted scope and storage baseline. Inventory permitted media and selected directories. For a new photo, record source URI, provider identity, size and SHA-256 locally. Exercise send, receive, save/download, edit, forward and export as separate actions, taking a fresh accessible inventory after each action. Repeat after an app restart and device reboot.

Compare full hashes and normalized identities. Distinguish newly persistent files from alternative URIs pointing to an existing asset. Recompressed images are not exact byte duplicates. Document permission limitations and inaccessible locations; absence from an accessible inventory is not evidence about private app caches. Never describe this experiment as deleted-file recovery or secure-erasure verification.

Store only synthetic test media and anonymized aggregate results in Git. A future report should state which workflow created which accessible independent copies, with device and app versions, rather than generalizing to every messaging app or Android device.
