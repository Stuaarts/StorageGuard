package com.storageguard.core

import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class Source { MEDIA, DOCUMENT }
enum class Category { PHOTOS, VIDEOS, OTHER }

data class Asset(
    val uri: String,
    val identity: String?,
    val name: String,
    val mime: String,
    val size: Long?,
    val modified: Long?,
    val location: String,
    val source: Source,
    val removable: Boolean,
) {
    val category: Category get() = when {
        mime.startsWith("image/") -> Category.PHOTOS
        mime.startsWith("video/") -> Category.VIDEOS
        else -> Category.OTHER
    }
}

interface StorageAccess {
    fun open(asset: Asset): InputStream
    fun refresh(asset: Asset): Asset?
}

data class HashedAsset(val asset: Asset, val sha256: String)
data class DuplicateGroup(val id: String, val files: List<HashedAsset>) {
    val size: Long get() = files.first().asset.size ?: 0
    // Unknown identities remain inspectable but are never counted as independent copies.
    val independent: List<HashedAsset> get() = files.filter { it.asset.identity != null }
        .distinctBy { it.asset.identity }
    val possibleBytes: Long get() {
        val known = independent
        val removable = known.count { it.asset.removable }
        return size * minOf(removable, (known.size - 1).coerceAtLeast(0))
    }
}
data class ScanProgress(
    val phase: String = "Ready",
    val discovered: Int = 0,
    val candidates: Int = 0,
    val hashed: Int = 0,
    val bytesRead: Long = 0,
    val issues: Int = 0,
)
data class ScanResult(
    val inventory: List<Asset>,
    val hashed: List<HashedAsset>,
    val groups: List<DuplicateGroup>,
    val progress: ScanProgress,
)

class DuplicateScanner(private val storage: StorageAccess) {
    suspend fun scan(
        discover: suspend (suspend (Asset) -> Unit) -> Unit,
        checkpoint: suspend () -> Unit = {},
        onProgress: (ScanProgress) -> Unit = {},
    ): ScanResult {
        val inventory = linkedMapOf<String, Asset>()
        var progress = ScanProgress(phase = "Discovering accessible files")
        discover { asset ->
            currentCoroutineContext().ensureActive()
            checkpoint()
            // One MediaStore item and its SAF alias must never become two removable copies.
            val key = asset.identity ?: asset.uri
            val previous = inventory[key]
            if (previous == null || asset.source == Source.MEDIA) inventory[key] = asset
            progress = progress.copy(discovered = inventory.size)
            onProgress(progress)
        }
        val candidates = inventory.values.filter { it.size != null && it.size >= 0 }
            .groupBy { it.size }.values.filter { it.size > 1 }.flatten()
        progress = progress.copy(phase = "Comparing full file contents", candidates = candidates.size)
        onProgress(progress)
        val hashed = mutableListOf<HashedAsset>()
        for (asset in candidates) {
            currentCoroutineContext().ensureActive()
            checkpoint()
            try {
                val before = storage.refresh(asset)
                check(before != null && asset.sameVersion(before))
                val digest = sha256(storage.open(asset), checkpoint) { bytes ->
                    progress = progress.copy(bytesRead = progress.bytesRead + bytes)
                    onProgress(progress)
                }
                val after = storage.refresh(asset)
                check(after != null && before.sameVersion(after) && digest.second == asset.size)
                hashed += HashedAsset(asset, digest.first)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                progress = progress.copy(issues = progress.issues + 1)
            }
            progress = progress.copy(hashed = progress.hashed + 1)
            onProgress(progress)
        }
        val groups = hashed.groupBy { "${it.asset.size}:${it.sha256}" }
            .filterValues { it.size > 1 }.map { (id, files) -> DuplicateGroup(id, files) }
            .sortedByDescending { it.possibleBytes }
        progress = progress.copy(phase = "Complete")
        onProgress(progress)
        return ScanResult(inventory.values.toList(), hashed, groups, progress)
    }
}

fun Asset.sameVersion(other: Asset): Boolean = uri == other.uri && identity == other.identity &&
    size == other.size && modified == other.modified && name == other.name

suspend fun sha256(
    input: InputStream,
    checkpoint: suspend () -> Unit = {},
    onBytes: (Long) -> Unit = {},
): Pair<String, Long> = input.use {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(128 * 1024)
    var total = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        checkpoint()
        val count = it.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        digest.update(buffer, 0, count)
        total += count
        onBytes(count.toLong())
    }
    digest.digest().joinToString("") { b -> "%02x".format(b) } to total
}

data class Removal(val keeper: HashedAsset, val target: HashedAsset)

object KeepPolicy {
    const val MAX_BATCH = 50
    fun plan(groups: List<DuplicateGroup>, selected: Set<String>): List<Removal> {
        require(selected.isNotEmpty()) { "Select copies to remove first." }
        require(selected.size <= MAX_BATCH) { "Review at most $MAX_BATCH copies at a time." }
        val all = groups.flatMap { it.files }
        require(selected.all { uri -> all.any { it.asset.uri == uri } }) { "Findings have changed. Scan again." }
        val selectedIdentities = all.filter { it.asset.uri in selected }.map { it.asset.identity }
        require(selectedIdentities.none { it == null }) { "Provider identity is uncertain. Review only." }
        require(selectedIdentities.distinct().size == selected.size) { "Two references point to the same file." }
        return groups.flatMap { group ->
            val targets = group.files.filter { it.asset.uri in selected }
            if (targets.isEmpty()) return@flatMap emptyList()
            val keeper = group.independent.firstOrNull {
                it.asset.uri !in selected && it.asset.identity !in selectedIdentities
            } ?: error("Keep at least one independent copy in every group.")
            targets.map { target ->
                require(target.asset.removable) { "This provider does not support safe removal." }
                require(target.sha256 == keeper.sha256 && target.asset.size == keeper.asset.size)
                Removal(keeper, target)
            }
        }
    }
}

class Preflight(private val storage: StorageAccess) {
    suspend fun verify(removal: Removal) {
        val kept = removal.keeper
        val target = removal.target
        require(kept.asset.identity != null && target.asset.identity != null &&
            kept.asset.identity != target.asset.identity) { "Independent copies cannot be established." }
        require(target.asset.removable)
        require(kept.sha256 == target.sha256 && kept.asset.size == target.asset.size)
        for (file in listOf(kept, target)) {
            val current = storage.refresh(file.asset)
            check(current != null && file.asset.sameVersion(current) &&
                (!file.asset.removable || current.removable)) { "File changed or access was revoked. Scan again." }
            val (hash, bytes) = sha256(storage.open(file.asset))
            check(hash == file.sha256 && bytes == file.asset.size) { "File contents changed. Scan again." }
        }
        // Compare bytes too. Short reads are filled independently for each provider stream.
        storage.open(kept.asset).use { left ->
            storage.open(target.asset).use { right ->
                val a = ByteArray(128 * 1024)
                val b = ByteArray(a.size)
                var compared = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = fill(left, a)
                    val m = fill(right, b)
                    check(n == m && (0 until n).all { a[it] == b[it] }) { "Copies are no longer identical." }
                    if (n == 0) break
                    compared += n
                }
                check(compared == kept.asset.size) { "File length changed during verification." }
            }
        }
        for (file in listOf(kept, target)) {
            check(storage.refresh(file.asset)?.let { file.asset.sameVersion(it) } == true) {
                "File changed during verification. Scan again."
            }
        }
    }

    private suspend fun fill(input: InputStream, buffer: ByteArray): Int {
        var count = 0
        while (count < buffer.size) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer, count, buffer.size - count)
            if (n < 0) break
            if (n == 0) continue
            count += n
        }
        return count
    }
}
